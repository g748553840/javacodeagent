package com.javacodeagent.core.skill;

import lombok.extern.slf4j.Slf4j;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * 校验外部 Skill 注册 URL，阻止指向内网/回环/链路本地地址的请求。
 *
 * <p>{@code SkillController.registerSkill}/{@code ExternalSkillLoader} 允许任意来源
 * （REST 请求体或磁盘 yml 文件）配置一个 HTTP 端点，{@code HttpDelegatedSkill.execute()}
 * 会让服务端对该 URL 发起真实请求并把响应体透传给调用方——若不做校验，
 * 攻击者可注册指向内网服务或云平台元数据端点（如 169.254.169.254）的 URL，
 * 诱导服务器代为请求并窃取本不该暴露的数据，构成经典 SSRF。
 */
@Slf4j
public final class SkillUrlValidator {

    private SkillUrlValidator() {}

    /**
     * @throws IllegalArgumentException 若 URL 指向内网/回环/链路本地/多播地址，或 scheme 非 http(s)
     */
    public static void validateNotInternal(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid execution.url: " + url);
        }

        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("execution.url must use http/https scheme: " + url);
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("execution.url must have a host: " + url);
        }

        // AWS/GCP/Azure/阿里云等云平台的元数据服务统一使用此地址，
        // 是 SSRF 攻击窃取云凭证的首选目标，直接按主机名硬拒绝（不依赖 DNS 解析结果）
        if ("169.254.169.254".equals(host) || "metadata.google.internal".equalsIgnoreCase(host)) {
            throw new IllegalArgumentException("execution.url targets a cloud metadata endpoint: " + url);
        }

        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isLoopbackAddress() || addr.isSiteLocalAddress()
                    || addr.isLinkLocalAddress() || addr.isMulticastAddress()
                    || addr.isAnyLocalAddress()) {
                throw new IllegalArgumentException(
                    "execution.url resolves to a private/internal address (" + addr.getHostAddress() + "): " + url);
            }
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("execution.url host could not be resolved: " + host);
        }
    }
}
