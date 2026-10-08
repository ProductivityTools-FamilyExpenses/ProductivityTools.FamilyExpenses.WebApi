package top.productivitytools.spendings.webapi.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

import java.util.List;

/**
 * Secures the API with Firebase Authentication ID tokens (sent as {@code Authorization: Bearer <idToken>}).
 * <p>
 * Firebase ID tokens are standard JWTs issued by {@code https://securetoken.google.com/<projectId>}
 * with {@code aud = <projectId>}, so Spring's OAuth2 resource server can validate them via OIDC discovery.
 * <ul>
 *   <li>POST endpoints (ingest from Apps Script) stay open for now, as requested.</li>
 *   <li>Everything else under {@code /api/**} (reads) requires a valid token.</li>
 *   <li>Optionally restrict access to a list of e-mails via {@code app.security.allowed-emails}.</li>
 * </ul>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Value("${app.firebase.project-id}")
    private String firebaseProjectId;

    @Value("${app.security.allowed-emails:}")
    private List<String> allowedEmails;

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(Customizer.withDefaults())
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Data ingestion from Google Apps Script – intentionally unauthenticated for now
                        .requestMatchers(HttpMethod.POST, "/api/**").permitAll()
                        // Simple liveness probe
                        .requestMatchers(HttpMethod.GET, "/api/debug/hello").permitAll()
                        // All reads require a Firebase ID token
                        .anyRequest().authenticated()
                )
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));

        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {
        String issuer = "https://securetoken.google.com/" + firebaseProjectId;
        NimbusJwtDecoder decoder = (NimbusJwtDecoder) JwtDecoders.fromOidcIssuerLocation(issuer);

        OAuth2TokenValidator<Jwt> withIssuer = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> withAudience = new AudienceValidator(firebaseProjectId);
        OAuth2TokenValidator<Jwt> withAllowedEmail = new AllowedEmailValidator(allowedEmails);

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(withIssuer, withAudience, withAllowedEmail));
        return decoder;
    }

    /** Rejects tokens issued for a different Firebase project. */
    private record AudienceValidator(String audience) implements OAuth2TokenValidator<Jwt> {
        @Override
        public OAuth2TokenValidatorResult validate(Jwt jwt) {
            if (jwt.getAudience().contains(audience)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "The required audience is missing", null));
        }
    }

    /** If an allow-list is configured, only those (verified) e-mails may access the API. */
    private record AllowedEmailValidator(List<String> allowedEmails) implements OAuth2TokenValidator<Jwt> {
        @Override
        public OAuth2TokenValidatorResult validate(Jwt jwt) {
            if (allowedEmails == null || allowedEmails.isEmpty()) {
                return OAuth2TokenValidatorResult.success();
            }
            String email = jwt.getClaimAsString("email");
            Boolean verified = jwt.getClaimAsBoolean("email_verified");
            if (email != null && Boolean.TRUE.equals(verified)
                    && allowedEmails.stream().anyMatch(e -> e.equalsIgnoreCase(email))) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "User is not allowed to access this API", null));
        }
    }
}
