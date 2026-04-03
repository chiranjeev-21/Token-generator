package com.tokengen.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.util.Hashtable;
import java.util.Set;

/**
 * Email validation controlled by requireWorkEmail:
 *
 * 1. If requireWorkEmail is false, only basic email shape is checked.
 *
 * 2. If requireWorkEmail is true, the address must not be a personal or
 *    disposable provider and the domain must have mail records.
 */
@Service
public class EmailValidationService {

    private static final Logger log = LoggerFactory.getLogger(EmailValidationService.class);

    private static final Set<String> PERSONAL_DOMAINS = Set.of(
        "gmail.com", "googlemail.com",
        "hotmail.com", "hotmail.in", "hotmail.co.uk", "hotmail.fr", "hotmail.de",
        "outlook.com", "outlook.in", "outlook.co.uk", "live.com", "live.in",
        "live.co.uk", "msn.com",
        "icloud.com", "me.com", "mac.com",
        "yahoo.com", "yahoo.in", "yahoo.co.in", "yahoo.co.uk", "yahoo.fr",
        "yahoo.de", "yahoo.co.jp", "ymail.com",
        "aol.com", "verizon.net",
        "protonmail.com", "protonmail.ch", "proton.me", "tutanota.com",
        "tutanota.de", "tutamail.com", "pm.me",
        "rediffmail.com",
        "yandex.com", "yandex.ru", "yandex.ua", "mail.ru", "bk.ru", "list.ru",
        "mail.com", "gmx.com", "gmx.net", "gmx.de", "zoho.com",
        "inbox.com", "lycos.com", "fastmail.com", "hushmail.com"
    );

    private static final Set<String> DISPOSABLE_DOMAINS = Set.of(
        "mailinator.com", "guerrillamail.com", "guerrillamailblock.com",
        "tempmail.com", "temp-mail.org", "temp-mail.io", "tempr.email",
        "yopmail.com", "yopmail.fr", "trashmail.com", "trashmail.at",
        "trashmail.me", "trashmail.io", "trashmail.xyz", "maildrop.cc",
        "fakeinbox.com", "throwaway.email", "discard.email", "mailnull.com",
        "getnada.com", "getairmail.com", "spamgap.com", "spamherelots.com",
        "spam4.me", "spamfree24.org", "spambox.us", "spamevader.com",
        "10minutemail.com", "10minutemail.net", "20minutemail.com",
        "minutemail.com", "tempmailo.com", "sharklasers.com", "guerrillamail.info",
        "filzmail.com", "mailnesia.com", "dispostable.com"
    );

    /**
     * @param email the email to validate
     * @param requireWorkEmail when true, reject personal email providers like Gmail/Yahoo/Outlook
     */
    public void validate(String email, boolean requireWorkEmail) {
        if (email == null || !email.contains("@")) {
            throw new EmailValidationException("Please enter a valid email address.");
        }

        String domain = email.substring(email.lastIndexOf('@') + 1).toLowerCase().trim();

        if (!domain.contains(".")) {
            throw new EmailValidationException("Please enter a valid email address.");
        }

        if (!requireWorkEmail) {
            return;
        }

        // Layer 1: Block personal providers for work-email-only clients
        if (requireWorkEmail && PERSONAL_DOMAINS.contains(domain)) {
            throw new EmailValidationException(
                "\"@" + domain + "\" is a personal email provider and is not accepted. " +
                "Please use your company email — e.g. you@amazon.com, you@microsoft.com.");
        }

        // Layer 2: Block disposable emails for work-email-only clients
        if (DISPOSABLE_DOMAINS.contains(domain)) {
            throw new EmailValidationException(
                "Disposable email addresses are not allowed. Please use a real company email.");
        }

        // Layer 3: MX record check — does this domain actually receive email?
        if (!hasMxRecord(domain)) {
            throw new EmailValidationException(
                "The domain \"" + domain + "\" doesn't appear to have valid mail servers. " +
                "Please check for typos.");
        }
    }

    private boolean hasMxRecord(String domain) {
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("java.naming.provider.url", "dns://");
            env.put("com.sun.jndi.dns.timeout.initial", "3000");
            env.put("com.sun.jndi.dns.timeout.retries", "1");

            DirContext ctx = new InitialDirContext(env);
            Attributes attrs = ctx.getAttributes(domain, new String[]{"MX"});
            boolean hasMx = attrs.get("MX") != null;
            ctx.close();

            if (!hasMx) {
                DirContext ctx2 = new InitialDirContext(env);
                attrs = ctx2.getAttributes(domain, new String[]{"A"});
                hasMx = attrs.get("A") != null;
                ctx2.close();
            }

            log.debug("MX check domain={} hasMx={}", domain, hasMx);
            return hasMx;

        } catch (Exception e) {
            log.warn("MX lookup failed for domain={} — failing open: {}", domain, e.getMessage());
            return true; // fail open — don't block real users on DNS timeout
        }
    }

    public static class EmailValidationException extends RuntimeException {
        public EmailValidationException(String message) { super(message); }
    }
}
