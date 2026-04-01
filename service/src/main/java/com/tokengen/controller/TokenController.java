package com.tokengen.controller;

import com.tokengen.config.ClientRegistry;
import com.tokengen.config.ClientRegistry.ClientConfig;
import com.tokengen.service.EmailService;
import com.tokengen.service.EmailValidationService;
import com.tokengen.service.EmailValidationService.EmailValidationException;
import com.tokengen.service.OtpService;
import com.tokengen.service.OtpService.OtpValidationException;
import com.tokengen.service.TokenIssuerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/token")
@RequiredArgsConstructor
@Validated
public class TokenController {

    private final OtpService             otpService;
    private final EmailService           emailService;
    private final TokenIssuerService     tokenIssuerService;
    private final EmailValidationService emailValidationService;
    private final ClientRegistry         clientRegistry;

    @Value("${app.otp.ttl-minutes:10}")
    private int otpTtlMinutes;

    // ── GET /client/{clientId} ────────────────────────────────────────────────
    @GetMapping("/client/{clientId}")
    public ResponseEntity<ClientInfoResponse> getClientInfo(@PathVariable String clientId) {
        return clientRegistry.findById(clientId)
                .map(c -> ResponseEntity.ok(new ClientInfoResponse(
                        c.getId(), c.getName(),
                        c.getDescription() != null ? c.getDescription() : "",
                        c.isRequireWorkEmail(), c.getTokenTtlHours())))
                .orElse(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(new ClientInfoResponse("unknown", "Unknown App", "", false, 24)));
    }

    // ── POST /validate-email ──────────────────────────────────────────────────
    // Called from UI onBlur — runs MX check + domain rules, returns immediately.
    @PostMapping("/validate-email")
    public ResponseEntity<ApiResponse<Void>> validateEmail(
            @RequestBody ValidateEmailRequest request) {
        ClientConfig client = resolve(request.clientId());
        try {
            emailValidationService.validate(request.email(), client.isRequireWorkEmail());
            return ResponseEntity.ok(new ApiResponse<>(true, "Email looks good.", null, Instant.now()));
        } catch (EmailValidationException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new ApiResponse<>(false, e.getMessage(), null, Instant.now()));
        }
    }

    // ── POST /request-otp ─────────────────────────────────────────────────────
    @PostMapping("/request-otp")
    public ResponseEntity<ApiResponse<Void>> requestOtp(
            @Valid @RequestBody OtpRequest request) {
        ClientConfig client = resolve(request.clientId());
        try {
            emailValidationService.validate(request.email(), client.isRequireWorkEmail());
        } catch (EmailValidationException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new ApiResponse<>(false, e.getMessage(), null, Instant.now()));
        }
        // OTP key is scoped per client so the same email can get tokens for different apps
        String otpKey = request.email().toLowerCase() + ":" + client.getId();
        String otp    = otpService.generateAndStore(otpKey);
        emailService.sendOtp(request.email(), otp, otpTtlMinutes, client.getName());
        return ResponseEntity.ok(new ApiResponse<>(
                true,
                "Verification code sent to " + maskEmail(request.email()) +
                ". Valid for " + otpTtlMinutes + " minutes.",
                null, Instant.now()));
    }

    // ── POST /verify-otp ──────────────────────────────────────────────────────
    @PostMapping("/verify-otp")
    public ResponseEntity<ApiResponse<TokenResponse>> verifyOtp(
            @Valid @RequestBody VerifyOtpRequest request) {
        ClientConfig client = resolve(request.clientId());
        String otpKey = request.email().toLowerCase() + ":" + client.getId();
        try {
            otpService.validateAndConsume(otpKey, request.otp());
        } catch (OtpValidationException e) {
            return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                    .body(new ApiResponse<>(false, e.getMessage(), null, Instant.now()));
        }
        String token = tokenIssuerService.issueToken(request.email(), client);
        return ResponseEntity.ok(new ApiResponse<>(
                true, "Email verified. Your token is ready.",
                new TokenResponse(token, client.getTokenTtlHours(), client.getName(),
                        "Paste this token into " + client.getName() + ". Single use only."),
                Instant.now()));
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────
    public record ValidateEmailRequest(String email, String clientId) {}
    public record OtpRequest(
        @NotBlank @Email(message = "Please enter a valid email address") String email,
        String clientId
    ) {}
    public record VerifyOtpRequest(
        @NotBlank @Email String email,
        @NotBlank @Size(min = 4, max = 10) String otp,
        String clientId
    ) {}
    public record TokenResponse(String token, int expiresInHours, String appName, String instructions) {}
    public record ClientInfoResponse(String id, String name, String description, boolean requireWorkEmail, int tokenTtlHours) {}
    public record ApiResponse<T>(boolean success, String message, T data, Instant timestamp) {}

    // ── Helpers ───────────────────────────────────────────────────────────────
    private ClientConfig resolve(String clientId) {
        return clientRegistry.findById(clientId).orElse(defaultClient());
    }

    private ClientConfig defaultClient() {
        ClientConfig c = new ClientConfig();
        c.setId("default"); c.setName("App");
        c.setRequireWorkEmail(false); c.setTokenTtlHours(24);
        c.setClaims(Map.of("role", "USER"));
        return c;
    }

    private String maskEmail(String email) {
        int at = email.indexOf('@');
        return at <= 1 ? email : email.charAt(0) + "***" + email.substring(at);
    }
}