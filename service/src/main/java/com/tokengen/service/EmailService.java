package com.tokengen.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final RestClient restClient;

    @Value("${app.email.resend.api-key:}")
    private String resendApiKey;

    @Value("${app.email.resend.from-email:}")
    private String resendFromEmail;

    @Value("${app.email.resend.from-name:Interview Bank}")
    private String resendFromName;

    public EmailService(@Value("${app.email.resend.base-url:https://api.resend.com}") String baseUrl) {
        this.restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .defaultHeader(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
            .defaultHeader(HttpHeaders.USER_AGENT, "token-generator/1.0")
            .build();
    }

    public void sendOtp(String toEmail, String otp, int ttlMinutes, String appName) {
        if (resendApiKey == null || resendApiKey.isBlank()) {
            throw new RuntimeException("RESEND_API_KEY is not configured.");
        }
        if (resendFromEmail == null || resendFromEmail.isBlank()) {
            throw new RuntimeException("RESEND_FROM_EMAIL is not configured.");
        }

        try {
            ResendSendEmailRequest request = new ResendSendEmailRequest(
                buildFromHeader(),
                List.of(toEmail),
                "Your verification code - " + appName,
                buildHtml(otp, ttlMinutes, appName)
            );

            ResendSendEmailResponse response = restClient.post()
                .uri("/emails")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + resendApiKey)
                .body(request)
                .retrieve()
                .body(ResendSendEmailResponse.class);

            log.info(
                "OTP email sent to={} for app={} resendId={}",
                toEmail,
                appName,
                response != null ? response.id() : "unknown"
            );
        } catch (RestClientResponseException e) {
            log.error(
                "Failed to send OTP to={} for app={} status={} body={}",
                toEmail,
                appName,
                e.getStatusCode(),
                e.getResponseBodyAsString(),
                e
            );
            throw new RuntimeException("Failed to send verification email. Please try again.");
        } catch (Exception e) {
            log.error("Failed to send OTP to={} for app={}", toEmail, appName, e);
            throw new RuntimeException("Failed to send verification email. Please try again.");
        }
    }

    private String buildFromHeader() {
        return resendFromName == null || resendFromName.isBlank()
            ? resendFromEmail
            : resendFromName + " <" + resendFromEmail + ">";
    }

    private String buildHtml(String otp, int ttlMinutes, String appName) {
        return "<!DOCTYPE html><html><head><meta charset=\"UTF-8\">"
            + "<style>"
            + "body{font-family:'Helvetica Neue',Arial,sans-serif;background:#0d1117;margin:0;padding:40px 20px}"
            + ".wrap{max-width:500px;margin:0 auto;background:#161b22;border-radius:14px;border:1px solid rgba(255,255,255,0.07);overflow:hidden}"
            + ".hdr{background:linear-gradient(135deg,#6366f1,#7c3aed);padding:28px 36px}"
            + ".hdr h1{color:#fff;margin:0;font-size:20px;font-weight:700}"
            + ".hdr p{color:rgba(255,255,255,0.7);margin:5px 0 0;font-size:13px}"
            + ".body{padding:36px}"
            + ".body p{color:#94a3b8;font-size:14px;line-height:1.65;margin:0 0 20px}"
            + ".code-box{background:#0d1117;border:1px solid rgba(255,255,255,0.08);border-radius:10px;padding:24px;text-align:center;margin:0 0 20px}"
            + ".code{font-family:'Courier New',monospace;font-size:40px;font-weight:800;color:#818cf8;letter-spacing:14px}"
            + ".ttl{color:#64748b;font-size:12px;margin-top:10px}"
            + ".note{background:#1e2535;border-left:3px solid #f59e0b;border-radius:4px;padding:12px 14px;color:#94a3b8;font-size:12px;line-height:1.55}"
            + ".ftr{padding:18px 36px;border-top:1px solid rgba(255,255,255,0.06);color:#334155;font-size:11px;text-align:center}"
            + "</style></head><body>"
            + "<div class=\"wrap\">"
            + "<div class=\"hdr\"><h1>" + escapeHtml(appName) + "</h1><p>Email Verification Code</p></div>"
            + "<div class=\"body\">"
            + "<p>Here is your one-time verification code:</p>"
            + "<div class=\"code-box\"><div class=\"code\">" + otp + "</div>"
            + "<div class=\"ttl\">Expires in " + ttlMinutes + " minutes</div></div>"
            + "<div class=\"note\">This code is for a single use. If you didn't request it, ignore this email.</div>"
            + "</div>"
            + "<div class=\"ftr\">This is an automated message. Do not reply to this email.</div>"
            + "</div></body></html>";
    }

    private String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private record ResendSendEmailRequest(
        String from,
        List<String> to,
        String subject,
        String html
    ) {}

    private record ResendSendEmailResponse(String id) {}
}
