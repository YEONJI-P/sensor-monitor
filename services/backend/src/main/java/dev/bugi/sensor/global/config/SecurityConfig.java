package dev.bugi.sensor.global.config;

import dev.bugi.sensor.auth.filter.JwtFilter;
import dev.bugi.sensor.global.security.CustomAccessDeniedHandler;
import dev.bugi.sensor.global.security.CustomAuthenticationEntryPoint;
import dev.bugi.sensor.sensordata.security.IngestKeyFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import static org.springframework.security.config.Customizer.withDefaults;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtFilter jwtFilter;
    private final CustomAuthenticationEntryPoint authenticationEntryPoint;
    private final CustomAccessDeniedHandler accessDeniedHandler;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, IngestKeyFilter ingestKeyFilter) throws Exception {
        http
                .cors(withDefaults())
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                )
                .authorizeHttpRequests(auth -> auth
                        // 정적 파일 & Swagger & 인증 불필요 API
                        // 정적 자산은 공개(인증은 JS가 JWT로 API 호출 시 수행). 페이지 접근 제어는 프론트가 역할로 처리.
                        .requestMatchers("/", "/index.html", "/dashboard.html", "/console.html")
                        .permitAll()
                        .requestMatchers("/css/**", "/js/**", "/favicon.ico", "/favicon.svg")
                        .permitAll()
                        .requestMatchers("/swagger-ui/**", "/api-docs/**", "/v3/api-docs/**")
                        .permitAll()
                        // health 감시용 — 상태만 노출(show-details: never)
                        .requestMatchers(HttpMethod.GET, "/actuator/health")
                        .permitAll()
                        .requestMatchers(HttpMethod.GET, "/auth/factories")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/auth/register", "/auth/login", "/auth/refresh")
                        .permitAll()

                        // 센서 수신 — JWT 역할과 분리한 공유 키 authority만 허용
                        .requestMatchers(HttpMethod.POST, "/sensor-data")
                        .hasAuthority("INGEST")

                        // 공장 관리 — SYSTEM_ADMIN만
                        .requestMatchers("/admin/factory-calendars/**")
                        .hasAnyRole("SYSTEM_ADMIN", "FACTORY_ADMIN")
                        .requestMatchers("/admin/factories/**")
                        .hasRole("SYSTEM_ADMIN")

                        // 구역 관리 — SYSTEM_ADMIN, FACTORY_ADMIN
                        .requestMatchers("/admin/zones/**")
                        .hasAnyRole("SYSTEM_ADMIN", "FACTORY_ADMIN")

                        // 기존 어드민 — SYSTEM_ADMIN, FACTORY_ADMIN
                        .requestMatchers("/admin/**")
                        .hasAnyRole("SYSTEM_ADMIN", "FACTORY_ADMIN")

                        // SSE 스트림 — 토큰 쿼리파라미터로 컨트롤러에서 직접 인증
                        .requestMatchers(HttpMethod.GET, "/dashboard/stream")
                        .permitAll()

                        // 장치 중심 overview — 운영 데이터 조회 역할만 허용
                        .requestMatchers(HttpMethod.GET, "/dashboard/overview")
                        .hasAnyRole("SYSTEM_ADMIN", "SYSTEM_VIEWER", "FACTORY_ADMIN", "MEMBER", "VIEWER")

                        // 센서 데이터 조회 — 접근 가능한 장치 범위는 서비스 계층에서 스코핑
                        .requestMatchers(HttpMethod.GET, "/sensor-data", "/sensor-data/**")
                        .hasAnyRole("SYSTEM_ADMIN", "SYSTEM_VIEWER", "FACTORY_ADMIN", "MEMBER", "VIEWER")

                        // 알림 조회 — 접근 가능한 장치 범위는 서비스 계층에서 스코핑
                        .requestMatchers(HttpMethod.GET, "/alerts", "/alerts/**")
                        .hasAnyRole("SYSTEM_ADMIN", "SYSTEM_VIEWER", "FACTORY_ADMIN", "MEMBER", "VIEWER")

                        // alarm episode 조회는 운영 조회 역할 전체, ack는 읽기 전용 역할을 제외한다.
                        .requestMatchers(HttpMethod.POST, "/alarm-episodes/*/ack")
                        .hasAnyRole("SYSTEM_ADMIN", "FACTORY_ADMIN", "MEMBER")
                        .requestMatchers(HttpMethod.GET, "/alarm-episodes", "/alarm-episodes/**")
                        .hasAnyRole("SYSTEM_ADMIN", "SYSTEM_VIEWER", "FACTORY_ADMIN", "MEMBER", "VIEWER")

                        // 채널 조회 — 센서 데이터 조회와 같은 계열(읽기 전용 역할 허용)
                        .requestMatchers(HttpMethod.GET, "/channels", "/channels/**")
                        .hasAnyRole("SYSTEM_ADMIN", "SYSTEM_VIEWER", "FACTORY_ADMIN", "MEMBER", "VIEWER")

                        // 구역 선택용 조회 — 역할별 범위는 AccessControlService에서 스코핑
                        .requestMatchers(HttpMethod.GET, "/zones")
                        .hasAnyRole("SYSTEM_ADMIN", "SYSTEM_VIEWER", "FACTORY_ADMIN", "MEMBER", "VIEWER")

                        // 채널 변경(등록·임계 수정) — 장치 변경과 같은 계열. 읽기 전용 역할은 제외.
                        // 등록은 /devices/{deviceId}/channels 아래라 device POST 규칙보다 먼저 둔다.
                        .requestMatchers(HttpMethod.POST, "/devices/*/channels")
                        .hasAnyRole("SYSTEM_ADMIN", "FACTORY_ADMIN", "MEMBER")
                        .requestMatchers(HttpMethod.PUT, "/channels/**")
                        .hasAnyRole("SYSTEM_ADMIN", "FACTORY_ADMIN", "MEMBER")

                        // 장치 변경(등록·수정·삭제) — SYSTEM_ADMIN, 소속 공장 FACTORY_ADMIN, 소속 구역 MEMBER.
                        // 세부 범위(공장/구역)는 서비스 계층에서 스코핑. 읽기 전용 역할은 제외.
                        .requestMatchers(HttpMethod.POST, "/devices")
                        .hasAnyRole("SYSTEM_ADMIN", "FACTORY_ADMIN", "MEMBER")
                        .requestMatchers(HttpMethod.PUT, "/devices/**")
                        .hasAnyRole("SYSTEM_ADMIN", "FACTORY_ADMIN", "MEMBER")
                        .requestMatchers(HttpMethod.DELETE, "/devices/**")
                        .hasAnyRole("SYSTEM_ADMIN", "FACTORY_ADMIN", "MEMBER")

                        // 나머지 인증 필요
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(ingestKeyFilter, JwtFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public IngestKeyFilter ingestKeyFilter(@Value("${ingest.api-key}") String ingestApiKey) {
        return new IngestKeyFilter(ingestApiKey);
    }

    @Bean
    public FilterRegistrationBean<IngestKeyFilter> ingestKeyFilterRegistration(IngestKeyFilter filter) {
        FilterRegistrationBean<IngestKeyFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
}
