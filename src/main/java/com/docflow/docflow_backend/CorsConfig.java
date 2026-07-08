/* package com.docflow.docflow_backend;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins("http://localhost:5173")
                .allowedMethods("GET", "POST", "PUT", "DELETE")
                .allowedHeaders("*");
    }
}

 */

package com.docflow.docflow_backend;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

@Configuration
public class CorsConfig {

    @Bean
    public CorsFilter corsFilter() {
        CorsConfiguration config = new CorsConfiguration();

        // Use pattern to cover all Vercel deployments (preview + production)
        // More secure than "*" but covers all *.vercel.app subdomains
        config.addAllowedOriginPattern("https://*.vercel.app");
        config.addAllowedOriginPattern("http://localhost:*"); // local dev any port

        config.addAllowedHeader("*");
        config.addAllowedMethod("*"); // covers GET, POST, OPTIONS (preflight), etc.

        // Expose headers needed for file download on mobile browsers
        config.addExposedHeader("Content-Disposition");
        config.addExposedHeader("Content-Length");
        config.addExposedHeader("Content-Type");

        // Do NOT set allowCredentials(true) unless you're using cookies/auth
        // Setting it to false (default) removes strict origin matching requirements
        config.setAllowCredentials(false);

        // Cache preflight response for 1 hour — reduces OPTIONS round trips on mobile
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);

        return new CorsFilter(source);
    }
}
