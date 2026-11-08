package de.in.mp.security;

import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * Loads local login accounts from the database and maps them to Spring
 * Security {@link UserDetails}.
 * <p>
 * OAuth2-only accounts have no password hash; the empty placeholder keeps the
 * {@link UserDetails} contract intact while the BCrypt matcher rejects every
 * submitted password for such accounts.
 */
@Service
public class UserDetailsServiceImpl implements UserDetailsService {

    private final AppUserRepository appUserRepository;

    public UserDetailsServiceImpl(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        AppUser appUser = appUserRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException("Unknown user: " + username));

        String passwordHash = appUser.getPasswordHash() != null ? appUser.getPasswordHash() : "";

        return User.builder()
                .username(appUser.getUsername())
                .password(passwordHash)
                .disabled(!appUser.isEnabled())
                .authorities(new SimpleGrantedAuthority(appUser.getRole().name()))
                .build();
    }
}
