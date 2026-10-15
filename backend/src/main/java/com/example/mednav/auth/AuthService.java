package com.example.mednav.auth;

import com.example.mednav.api.ApiException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("[\\p{L}\\p{N}._-]{3,64}");
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_BCRYPT_BYTES = 72;

    private final UserAccountMapper accountMapper;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserAccountMapper accountMapper, PasswordEncoder passwordEncoder) {
        this.accountMapper = accountMapper;
        this.passwordEncoder = passwordEncoder;
    }

    public UsernameLookup lookupUsername(String rawUsername) {
        String username = normalizeDisplayUsername(rawUsername);
        String canonical = canonicalize(username);
        return new UsernameLookup(
                username,
                canonical,
                accountMapper.findByCanonicalUsername(canonical) != null);
    }

    @Transactional
    public AuthenticatedUser register(String rawUsername, String password) {
        String username = normalizeDisplayUsername(rawUsername);
        String canonical = canonicalize(username);
        validatePassword(password);
        Instant now = Instant.now();
        UserAccount account = new UserAccount(
                UUID.randomUUID().toString(),
                username,
                canonical,
                passwordEncoder.encode(password),
                "ACTIVE",
                now,
                now);
        try {
            accountMapper.insert(account);
        } catch (DuplicateKeyException exception) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "USERNAME_ALREADY_EXISTS",
                    "This username was registered by another request. Please sign in instead.");
        }
        return new AuthenticatedUser(account.id(), account.username(), true);
    }

    public AuthenticatedUser login(String rawUsername, String password) {
        String canonical = canonicalize(normalizeDisplayUsername(rawUsername));
        UserAccount account = accountMapper.findByCanonicalUsername(canonical);
        if (account == null || password == null || !"ACTIVE".equals(account.accountStatus())
                || !passwordEncoder.matches(password, account.passwordHash())) {
            throw new ApiException(
                    HttpStatus.UNAUTHORIZED,
                    "INVALID_CREDENTIALS",
                    "The username or password is incorrect.");
        }
        return new AuthenticatedUser(account.id(), account.username(), false);
    }

    private String normalizeDisplayUsername(String value) {
        String username = value == null ? "" : Normalizer.normalize(value.strip(), Normalizer.Form.NFKC);
        if (!USERNAME_PATTERN.matcher(username).matches()) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_USERNAME",
                    "Username must be 3-64 letters, numbers, dots, underscores, or hyphens.");
        }
        return username;
    }

    private String canonicalize(String username) {
        return username.toLowerCase(Locale.ROOT);
    }

    private void validatePassword(String password) {
        int byteLength = password == null ? 0 : password.getBytes(StandardCharsets.UTF_8).length;
        if (password == null || password.length() < MIN_PASSWORD_LENGTH || byteLength > MAX_BCRYPT_BYTES) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_PASSWORD",
                    "Password must contain at least 8 characters and at most 72 UTF-8 bytes.");
        }
    }
}
