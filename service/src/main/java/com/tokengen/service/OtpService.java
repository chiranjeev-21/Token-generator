package com.tokengen.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;

@Service
@RequiredArgsConstructor
public class OtpService {

    private static final Logger log = LoggerFactory.getLogger(OtpService.class);

    private static final String OTP_PREFIX      = "otp:";
    private static final String ATTEMPTS_PREFIX = "otp_attempts:";

    private final StringRedisTemplate redisTemplate;
    private final SecureRandom        secureRandom = new SecureRandom();

    @Value("${app.otp.length:6}")
    private int otpLength;

    @Value("${app.otp.ttl-minutes:10}")
    private int ttlMinutes;

    @Value("${app.otp.max-attempts:5}")
    private int maxAttempts;

    public String generateAndStore(String email) {
        String otp = generateOtp();
        String key = OTP_PREFIX + email.toLowerCase();
        Duration ttl = Duration.ofMinutes(ttlMinutes);
        redisTemplate.opsForValue().set(key, otp, ttl);
        redisTemplate.opsForValue().set(ATTEMPTS_PREFIX + email.toLowerCase(), "0", ttl);
        log.debug("OTP generated for email={} ttlMinutes={}", email, ttlMinutes);
        return otp;
    }

    public boolean validateAndConsume(String email, String submittedOtp) {
        String normEmail   = email.toLowerCase();
        String attemptsKey = ATTEMPTS_PREFIX + normEmail;
        String otpKey      = OTP_PREFIX + normEmail;

        String attemptsStr = redisTemplate.opsForValue().get(attemptsKey);
        int attempts = attemptsStr == null ? 0 : Integer.parseInt(attemptsStr);
        if (attempts >= maxAttempts) {
            throw new OtpValidationException("Too many failed attempts. Request a new OTP.");
        }

        String storedOtp = redisTemplate.opsForValue().get(otpKey);
        if (storedOtp == null) {
            throw new OtpValidationException("OTP has expired or was never requested. Please request a new one.");
        }

        if (!storedOtp.equals(submittedOtp.trim())) {
            redisTemplate.opsForValue().increment(attemptsKey);
            long remaining = maxAttempts - attempts - 1;
            throw new OtpValidationException("Incorrect OTP. " + remaining + " attempt(s) remaining.");
        }

        redisTemplate.delete(otpKey);
        redisTemplate.delete(attemptsKey);
        log.debug("OTP validated and consumed for email={}", normEmail);
        return true;
    }

    private String generateOtp() {
        int max  = (int) Math.pow(10, otpLength);
        int code = secureRandom.nextInt(max);
        return String.format("%0" + otpLength + "d", code);
    }

    public static class OtpValidationException extends RuntimeException {
        public OtpValidationException(String message) { super(message); }
    }
}
