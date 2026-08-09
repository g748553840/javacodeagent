package com.javacodeagent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "agent")
public class AgentConfig {
    /** Agent 循环最大工具调用深度，超出后返回错误。默认 50。 */
    private int maxToolCallDepth = 50;

    /** 工具批量执行策略。 */
    private ToolExecution tool = new ToolExecution();

    @Data
    public static class ToolExecution {

        /**
         * 是否允许同一批工具调用并行执行。默认开启。
         *
         * <p>关掉它等于回到逐个执行的老行为，是排查「疑似并发导致的诡异结果」时
         * 的第一个开关。关闭不影响正确性，只影响延迟——声明 {@code SEQUENTIAL}
         * 的工具在开启时也仍然是串行的。
         */
        private boolean parallelEnabled = true;

        /**
         * 并行执行的最大并发度。默认 4。
         *
         * <p>不设成无上限：一批里出现 20 个 Read 时，同时打开 20 个文件读取
         * 对磁盘是负优化；而且每个并发工具都占一个 boundedElastic 线程，
         * 会与同进程的 JPA 持久化抢线程。4 足够吃掉大部分等待，
         * 又不至于把线程池打满。
         */
        private int maxParallelism = 4;
    }
}
