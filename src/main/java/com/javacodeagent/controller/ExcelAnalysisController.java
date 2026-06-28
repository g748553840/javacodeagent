package com.javacodeagent.controller;

import com.javacodeagent.core.data.DataAgentConstants;
import com.javacodeagent.core.data.ExcelDataSourceConnector;
import com.javacodeagent.core.data.InsightGenerator;
import com.javacodeagent.core.data.Nl2SqlService;
import com.javacodeagent.core.data.SqlValidator;
import com.javacodeagent.core.data.model.ChartSpec;
import com.javacodeagent.core.data.model.DataAnalysisReport;
import com.javacodeagent.core.data.model.SqlValidationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/**
 * Excel / CSV 文件上传与 NL 查询端点。
 *
 * <pre>
 * POST /api/v1/data-agent/excel/upload   multipart/form-data, file=xxx.xlsx
 *   → {"tableName":"tbl_a1b2c3d4","filename":"sales.xlsx","schema":"...","status":"imported"}
 *
 * POST /api/v1/data-agent/excel/query    application/json
 *   → {"tableName":"tbl_a1b2c3d4","question":"各产品销量排名"}
 *   → DataAnalysisReport
 * </pre>
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/data-agent/excel")
@RequiredArgsConstructor
public class ExcelAnalysisController {

    private final ExcelDataSourceConnector excelConnector;
    private final Nl2SqlService nl2SqlService;
    private final SqlValidator sqlValidator;
    private final InsightGenerator insightGenerator;

    /**
     * 上传 Excel / CSV 文件，导入 H2 内存表，返回表名供后续查询。
     * Content-Type: multipart/form-data; file=&lt;file&gt;
     */
    @PostMapping("/upload")
    public Mono<ResponseEntity<Map<String, Object>>> upload(@RequestPart("file") FilePart filePart) {
        String filename = filePart.filename();
        return filePart.content()
            .reduce(new byte[0], (acc, buf) -> {
                try {
                    byte[] chunk = new byte[buf.readableByteCount()];
                    buf.read(chunk);
                    byte[] merged = new byte[acc.length + chunk.length];
                    System.arraycopy(acc, 0, merged, 0, acc.length);
                    System.arraycopy(chunk, 0, merged, acc.length, chunk.length);
                    return merged;
                } finally {
                    DataBufferUtils.release(buf);
                }
            })
            .flatMap(bytes -> Mono.fromCallable(() -> {
                String tableName = excelConnector.importFile(bytes, filename);
                String schema = excelConnector.getTableInfo(tableName);
                return ResponseEntity.ok(Map.<String, Object>of(
                    "tableName", tableName,
                    "filename", filename,
                    "schema", schema,
                    "status", "imported"
                ));
            }).subscribeOn(Schedulers.boundedElastic()))
            .onErrorResume(e -> {
                log.error("Excel import failed for '{}': {}", filename, e.getMessage());
                return Mono.just(ResponseEntity.badRequest()
                    .body(Map.of("error", e.getMessage(), "status", "failed")));
            });
    }

    /**
     * 对已上传的 Excel 表执行自然语言查询。
     *
     * <p>请求体（application/json）：
     * <pre>{"tableName": "tbl_a1b2c3d4", "question": "各产品销量排名"}</pre>
     * tableName 由 {@code /upload} 端点返回。
     */
    @PostMapping("/query")
    public Mono<ResponseEntity<DataAnalysisReport>> queryExcel(
            @RequestBody Map<String, String> body) {

        String tableName = body.get("tableName");
        String question  = body.get("question");

        if (tableName == null || tableName.isBlank()) {
            return Mono.just(ResponseEntity.badRequest()
                .body(DataAnalysisReport.error("tableName is required")));
        }
        if (question == null || question.isBlank()) {
            return Mono.just(ResponseEntity.badRequest()
                .body(DataAnalysisReport.error("question is required")));
        }

        // getTableInfo() 是阻塞 JDBC 调用，必须在 boundedElastic 线程执行，
        // 不能直接在 Netty IO 线程调用后再进入响应式链
        return Mono.fromCallable(() -> excelConnector.getTableInfo(tableName))
            .subscribeOn(Schedulers.boundedElastic())
            .flatMap(schema -> nl2SqlService.generateSql(question, schema))
            .flatMap(nl2SqlResult -> {
                SqlValidationResult valid = sqlValidator.validate(nl2SqlResult.getSql());
                if (!valid.isAllowed()) {
                    return Mono.just(DataAnalysisReport.error(question, "SQL rejected: " + valid.reason()));
                }
                return Mono.fromCallable(() ->
                    excelConnector.query(nl2SqlResult.getSql(), DataAgentConstants.DEFAULT_MAX_ROWS)
                ).subscribeOn(Schedulers.boundedElastic())
                .flatMap(qr -> {
                    ChartSpec chartSpec = ChartSpec.from(nl2SqlResult, qr);
                    return insightGenerator.generate(question, chartSpec)
                        .map(insight -> DataAnalysisReport.builder()
                            .question(question)
                            .chartSpec(insight.chartSpec())
                            .insightMarkdown(insight.markdown())
                            .success(true)
                            .build());
                });
            })
            .map(ResponseEntity::ok)
            .onErrorResume(e -> {
                log.error("Excel query failed for table '{}': {}", tableName, e.getMessage());
                return Mono.just(ResponseEntity.badRequest()
                    .body(DataAnalysisReport.error(question, e.getMessage())));
            });
    }
}
