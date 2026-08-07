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
        // 累计已读字节数并提前中断，避免超大文件在达到上限前继续占用内存；
        // DataBufferUtils.join 内部按需增长缓冲区，不做 O(n²) 的手工数组拷贝
        long[] totalBytes = {0};
        return filePart.content()
            .handle((buf, sink) -> {
                totalBytes[0] += buf.readableByteCount();
                if (totalBytes[0] > DataAgentConstants.EXCEL_UPLOAD_MAX_BYTES) {
                    DataBufferUtils.release(buf);
                    sink.error(new IllegalArgumentException(
                        "File exceeds max upload size of " + (DataAgentConstants.EXCEL_UPLOAD_MAX_BYTES / (1024 * 1024)) + "MB"));
                } else {
                    sink.next(buf);
                }
            })
            .as(DataBufferUtils::join)
            .map(buf -> {
                try {
                    byte[] bytes = new byte[buf.readableByteCount()];
                    buf.read(bytes);
                    return bytes;
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
            // tableName 作为 SQL 缓存的隔离键：每次 /upload 都生成唯一表名，
            // 用它代替 dataSourceId 可防止不同上传文件的相同问法互相串用缓存的 SQL
            .flatMap(schema -> nl2SqlService.generateSql(tableName, question, schema))
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
                            .insightFailed(insight.failed())
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
