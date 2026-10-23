package com.akhester.auditui.page;

import java.util.Locale;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.i18n.LocaleChangeInterceptor;

import com.akhester.auditui.prefs.PrefsContext;
import com.akhester.auditui.prefs.UserPrefs;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Language: English, Sinhala, Tamil. Order of choice: the language cookie (set by the language switch or ?lang=xx),
 * then the person's saved preference, then English. Message files: messages.properties (English, complete),
 * messages_si.properties and messages_ta.properties (translators add keys; a missing key shows the English text).
 */
@Configuration
public class I18nConfig implements WebMvcConfigurer {

    static final String COOKIE = "AUDIT_LANG";

    @Bean
    public LocaleResolver localeResolver(PrefsContext prefs) {
        return new LocaleResolver() {
            @Override
            public Locale resolveLocale(HttpServletRequest request) {
                if (request.getCookies() != null) {
                    for (Cookie c : request.getCookies()) {
                        if (COOKIE.equals(c.getName()) && UserPrefs.LANGUAGES.contains(c.getValue())) {
                            return Locale.forLanguageTag(c.getValue());
                        }
                    }
                }
                return Locale.forLanguageTag(prefs.forRequest(request).language());
            }

            @Override
            public void setLocale(HttpServletRequest request, HttpServletResponse response, Locale locale) {
                String lang = locale == null ? null : locale.getLanguage();
                if (lang == null || !UserPrefs.LANGUAGES.contains(lang)) return;   // ignore anything not offered
                Cookie c = new Cookie(COOKIE, lang);
                c.setPath("/");
                c.setMaxAge(365 * 24 * 3600);
                c.setHttpOnly(true);
                c.setSecure(request.isSecure());
                c.setAttribute("SameSite", "Lax");
                response.addCookie(c);
                String user = PrefsContext.username();
                if (user != null) {
                    UserPrefs now = prefs.forRequest(request);
                    if (!lang.equals(now.language())) {
                        prefs.save(request, now.withLanguage(lang));
                    }
                }
            }
        };
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        LocaleChangeInterceptor i = new LocaleChangeInterceptor();
        i.setParamName("lang");
        i.setIgnoreInvalidLocale(true);
        registry.addInterceptor(i);
    }
}
