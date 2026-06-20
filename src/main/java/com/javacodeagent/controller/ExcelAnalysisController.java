package com.javacodeagent.controller;

import com.javacodeagent.core.data.DataAgentPipeline;
import com.javacodeagent.core.data.ExcelDataSourceConnector;
import com.javacodeagent.core.data.Nl2SqlService;
import com.javacodeagent.core.data.SqlExecutor;
import com.javacodeagent.core.data.SqlValidator;
import com.javacodeagent.core.data.model.DataAnalysisReport;
import com.javacodeagent.core.data.model.DataQueryRequest;
import com.javacodeagent.core.data.model.DataQueryResult;
import com.javacodeagent.core.data.model.SqlValidationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/data-agent/excel")
@RequiredArgsConstructor
public class ExcelAnalysisController {

    private final ExcelDataSourceConnector excelConnector;
    private final Nl2SqlService nl2SqlService;
    private final SqlValidator sqlValidator;
    private final SqlExecutor sqlExecutor;
    private final DataAgentPipeline pipeline;

    /**
     * 上传 Excel / CSV 文件，导入 H2 内存表，返回表名供后续查询。
     * Content-Type: multipart/form-data; file=<file>
     */
    @PostMapping("/upload")
    public Mono<ResponseEntity<Map<String, Object>>> upload(@RequestPart("file") FilePart filePart) {
        String filename = filePart.filename();
        return filePart.content()
            .reduce(new byte[0], (acc, buf) -> {
                byte[] chunk = new byte[buf.readableByteCount()];
                buf.read(chunk);
                byte[] merged = new byte[acc.length + chunk.length];
                System.arraycopy(acc, 0, merged, 0, acc.length);
                System.arraycopy(chunk, 0, merged, acc.length, chunk.length);
                return merged;
            })
            .flatMap(bytes -> Mono.fromCallable(() -> {
                String tableName = excelConnector.importFile(bytes, filename);
                String schema = excelConnector.getTableInfo(tableName);
                return ResponseEntity.ok(Map.of(
                    "tableName", tableName,
                    "filename", filename,
                    "schema", schema,
                    "status", "imported"
                ));
            }).subscribeOn(Schedulers.boundedElastic()))
            .onErrorResume(e -> {
                log.error("Excel import failed: {}", e.getMessage());
                return Mono.just(ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage(), "status", "failed")));
            });
    }

    /**
     * 对已上传的 Excel 表执行自然语言查询。
     * tableName 为 /upload 返回的 tableName 字段。
     */
    @PostMapping("/query")
    public Mono<ResponseEntity<DataAnalysisReport>> queryExcel(
            @RequestParam String tableName,
            @RequestParam String question) {

        String schema = excelConnector.getTableInfo(tableName);

        return nl2SqlService.generateSql(question, schema)
            .flatMap(nl2SqlResult -> {
                SqlValidationResult valid = sqlValidator.validate(nl2SqlResult.getSql());
                if (!valid.isAllowed()) {
                    return Mono.just(DataAnalysisReport.error("SQL rejected: " + valid.reason()));
                }
                return Mono.fromCallable(() ->
                    excelConnector.query(nl2SqlResult.getSql(), 200)
                ).subscribeOn(Schedulers.boundedElastic())
                .map(qr -> DataAnalysisReport.builder()
                    .question(question)
                    .chartSpec(com.javacodeagent.core.data.model.ChartSpec.from(nl2SqlResult, qr))
                    .insightMarkdown(nl2SqlResult.getThought())
                    .success(true)
                    .build());
            })
            .map(ResponseEntity::ok)
            .onErrorResume(e -> {
                log.error("Excel query failed: {}", e.getMessage());
                return Mono.just(ResponseEntity.badRequest()
                    .body(DataAnalysisReport.error(e.getMessage())));
            });
    }
}
