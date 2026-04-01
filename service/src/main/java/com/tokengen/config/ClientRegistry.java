package com.tokengen.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reads the app.clients list from application.yml and provides
 * lookup by clientId. Add a new entry to application.yml to
 * register a new project — no code changes needed.
 */
@Component
@ConfigurationProperties(prefix = "app")
@Getter
@Setter
public class ClientRegistry {

    private List<ClientConfig> clients = List.of();

    public Optional<ClientConfig> findById(String clientId) {
        if (clientId == null) return Optional.empty();
        return clients.stream()
                .filter(c -> c.getId().equalsIgnoreCase(clientId))
                .findFirst();
    }

    @Getter
    @Setter
    public static class ClientConfig {
        /** Unique ID for this app — used in ?app= URL param and aud JWT claim */
        private String id;

        /** Human-readable name shown in the email and on the token page */
        private String name;

        /** Optional description shown as subtitle */
        private String description;

        /** If true, blocks Gmail/Yahoo/Outlook/etc. — use for B2B or internal tools */
        private boolean requireWorkEmail = false;

        /** How long issued tokens are valid */
        private int tokenTtlHours = 24;

        /**
         * Custom claims to embed in the JWT.
         * Your consuming app can read these after signature verification.
         * Example: role=CONTRIBUTOR, tier=PRO, plan=ENTERPRISE
         */
        private Map<String, String> claims = Map.of();
    }
}