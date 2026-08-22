package dev.bugi.sensor.global.config;

import dev.bugi.sensor.admin.service.AdminService;
import dev.bugi.sensor.admin.service.ZoneService;
import dev.bugi.sensor.admin.service.FactoryService;
import dev.bugi.sensor.admin.service.FactoryCalendarAdminService;
import dev.bugi.sensor.alert.service.AlertService;
import dev.bugi.sensor.alert.service.AlarmEpisodeService;
import dev.bugi.sensor.auth.dto.FactoryOptionResponse;
import dev.bugi.sensor.auth.service.AuthService;
import dev.bugi.sensor.auth.util.JwtUtil;
import dev.bugi.sensor.dashboard.service.DashboardOverviewService;
import dev.bugi.sensor.device.service.ChannelService;
import dev.bugi.sensor.device.service.DeviceService;
import dev.bugi.sensor.global.security.CustomAccessDeniedHandler;
import dev.bugi.sensor.global.security.CustomAuthenticationEntryPoint;
import dev.bugi.sensor.global.service.AccessControlService;
import dev.bugi.sensor.sensordata.dto.BatchIngestResponse;
import dev.bugi.sensor.sensordata.dto.BatchIngestResult;
import dev.bugi.sensor.sensordata.failure.FailedReadingRepository;
import dev.bugi.sensor.sensordata.service.SensorDataService;
import dev.bugi.sensor.sse.SseService;
import dev.bugi.sensor.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
@Import({
        SecurityConfig.class,
        CorsConfig.class,
        CustomAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class
})
public class SecurityConfigTest {
    private static final String INGEST_KEY = "test-ingest-key";
    private static final String VALID_BODY =
            "{\"deviceCode\":\"CMAPSS-U1\",\"measurements\":{\"s4\":100.0}}";

    @Autowired
    MockMvc mockMvc;
    @MockitoBean
    SensorDataService sensorDataService;
    @MockitoBean
    ChannelService channelService;
    @MockitoBean
    AuthService authService;
    @MockitoBean
    DeviceService deviceService;
    @MockitoBean
    DashboardOverviewService dashboardOverviewService;
    @MockitoBean
    AlertService alertService;
    @MockitoBean
    AlarmEpisodeService alarmEpisodeService;
    @MockitoBean
    AdminService adminService;
    @MockitoBean
    ZoneService zoneService;
    @MockitoBean
    FactoryService factoryService;
    @MockitoBean
    FactoryCalendarAdminService factoryCalendarAdminService;
    @MockitoBean
    AccessControlService accessControlService;
    @MockitoBean
    SseService sseService;
    @MockitoBean
    UserRepository userRepository;
    @MockitoBean
    JwtUtil jwtUtil;
    // GlobalExceptionHandler(@RestControllerAdvice)가 요구하는 의존.
    @MockitoBean
    FailedReadingRepository failedReadingRepository;

    // -- ingest 공유 키 endpoint(C1: deviceCode + measurements) --
    @Test
    void post_sensor_data_with_valid_ingest_key_returns_200() throws Exception {
        given(sensorDataService.receive(any())).willReturn(result(BatchIngestResult.Outcome.SAVED));

        mockMvc.perform(post("/sensor-data")
                .header("X-Ingest-Key", INGEST_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
                .andExpect(status().isOk());
    }

    @Test
    void post_sensor_data_without_ingest_key_returns_contract_401() throws Exception {
        mockMvc.perform(post("/sensor-data")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("유효한 센서 수신 키가 필요합니다."));
        verifyNoInteractions(sensorDataService);
    }

    @Test
    void post_sensor_data_with_invalid_ingest_key_returns_contract_401() throws Exception {
        mockMvc.perform(post("/sensor-data")
                .header("X-Ingest-Key", "wrong-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("유효한 센서 수신 키가 필요합니다."));
        verifyNoInteractions(sensorDataService);
    }

    @Test
    void post_sensor_data_with_user_jwt_only_returns_401() throws Exception {
        given(jwtUtil.validateAccessToken("user-token")).willReturn(true);
        given(jwtUtil.getEmployeeId("user-token")).willReturn("member-1");
        given(jwtUtil.getRole("user-token")).willReturn("MEMBER");

        mockMvc.perform(post("/sensor-data")
                .header("Authorization", "Bearer user-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.message").value("유효한 센서 수신 키가 필요합니다."));
        verifyNoInteractions(sensorDataService);
    }

    @Test
    @WithMockUser(roles = "FACTORY_ADMIN")
    void post_sensor_data_factory_admin_without_ingest_key_returns_401() throws Exception {
        mockMvc.perform(post("/sensor-data")
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(sensorDataService);
    }

    // -- C2 계약: 잘못된 수신 요청은 400(500 아님) + 실패 적재 --
    @Test
    void post_sensor_data_invalid_returns_400_and_records_failure() throws Exception {
        mockMvc.perform(post("/sensor-data")
                .header("X-Ingest-Key", INGEST_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"deviceCode\":\"\",\"measurements\":{}}"))
                .andExpect(status().isBadRequest());
        verify(failedReadingRepository).save(any());
    }

    @Test
    void post_sensor_data_device_not_found_still_returns_404() throws Exception {
        given(sensorDataService.receive(any())).willReturn(result(BatchIngestResult.Outcome.DEVICE_NOT_FOUND));

        mockMvc.perform(post("/sensor-data")
                .header("X-Ingest-Key", INGEST_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
                .andExpect(status().isNotFound());
    }

    @Test
    void post_sensor_data_no_known_channels_still_returns_422() throws Exception {
        given(sensorDataService.receive(any())).willReturn(result(BatchIngestResult.Outcome.NO_KNOWN_CHANNELS));

        mockMvc.perform(post("/sensor-data")
                .header("X-Ingest-Key", INGEST_KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content(VALID_BODY))
                .andExpect(status().isUnprocessableEntity());
    }

    // -- 보호 endpoint --
    @Test
    void favicon_no_auth_is_public() throws Exception {
        mockMvc.perform(get("/favicon.svg"))
                .andExpect(status().isOk());
    }

    @Test
    void get_auth_factories_no_auth_returns_public_id_and_name_only() throws Exception {
        given(authService.getFactories()).willReturn(List.of(
                new FactoryOptionResponse(1L, "가 공장"),
                new FactoryOptionResponse(2L, "나 공장")
        ));

        mockMvc.perform(get("/auth/factories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1L))
                .andExpect(jsonPath("$[0].name").value("가 공장"))
                .andExpect(jsonPath("$[0].description").doesNotExist())
                .andExpect(jsonPath("$[0].createdAt").doesNotExist())
                .andExpect(jsonPath("$[1].id").value(2L))
                .andExpect(jsonPath("$[1].name").value("나 공장"));
    }

    @Test
    void register_without_factory_returns_400() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeId\":\"EMP001\",\"name\":\"홍길동\",\"password\":\"password123!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
        verifyNoInteractions(authService);
    }

    @Test
    void register_with_non_positive_factory_returns_400() throws Exception {
        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"employeeId\":\"EMP001\",\"name\":\"홍길동\",\"password\":\"password123!\",\"factoryId\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
        verifyNoInteractions(authService);
    }

    @Test
    void get_sensor_data_no_auth() throws Exception {
        mockMvc.perform(get("/sensor-data"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void get_channels_no_auth() throws Exception {
        mockMvc.perform(get("/channels"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void get_dashboard_overview_no_auth() throws Exception {
        mockMvc.perform(get("/dashboard/overview"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void get_dashboard_overview_member_ok() throws Exception {
        mockMvc.perform(get("/dashboard/overview"))
                .andExpect(status().is(not(403)));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void get_dashboard_overview_viewer_ok() throws Exception {
        mockMvc.perform(get("/dashboard/overview"))
                .andExpect(status().is(not(403)));
    }

    @Test
    @WithMockUser(roles = "SYSTEM_VIEWER")
    void get_dashboard_overview_system_viewer_ok() throws Exception {
        mockMvc.perform(get("/dashboard/overview"))
                .andExpect(status().is(not(403)));
    }

    @Test
    @WithMockUser(roles = "FACTORY_ADMIN")
    void get_dashboard_overview_factory_admin_ok() throws Exception {
        mockMvc.perform(get("/dashboard/overview"))
                .andExpect(status().is(not(403)));
    }

    // -- 역할 제한

    @Test
    @WithMockUser(roles = "SYSTEM_ADMIN")
    void get_admin_factory_with_system_admin() throws Exception {
        mockMvc.perform(get("/admin/factories"))
                .andExpect(status().is(not(403)));
    }

    @Test
    @WithMockUser(roles = "FACTORY_ADMIN")
    void get_admin_factory_with_factory_admin_is_still_forbidden() throws Exception {
        mockMvc.perform(get("/admin/factories"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(factoryService);
    }

    @Test
    void get_factory_calendar_without_auth_is_unauthorized() throws Exception {
        mockMvc.perform(get("/admin/factory-calendars"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(factoryCalendarAdminService);
    }

    @Test
    @WithMockUser(username = "SYSTEM", roles = "SYSTEM_ADMIN")
    void get_factory_calendar_with_system_admin_is_allowed() throws Exception {
        given(factoryCalendarAdminService.summaries("SYSTEM")).willReturn(List.of());
        mockMvc.perform(get("/admin/factory-calendars"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "ADMIN", roles = "FACTORY_ADMIN")
    void put_factory_calendar_with_factory_admin_is_allowed() throws Exception {
        mockMvc.perform(put("/admin/factory-calendars/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"timezone":"Asia/Seoul","resumeGraceSeconds":300,"revision":0,
                                 "weeklyIntervals":[],"dateOverrides":[]}
                                """))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void factory_calendar_with_member_is_forbidden() throws Exception {
        mockMvc.perform(get("/admin/factory-calendars"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(factoryCalendarAdminService);
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void factory_calendar_with_viewer_is_forbidden() throws Exception {
        mockMvc.perform(get("/admin/factory-calendars/1"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(factoryCalendarAdminService);
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void post_admin_factory_with_member_is_still_forbidden() throws Exception {
        mockMvc.perform(post("/admin/factories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"새 공장\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(factoryService);
    }

    @Test
    @WithMockUser(roles = "FACTORY_ADMIN")
    void post_admin_factory_with_factory_admin_is_still_forbidden() throws Exception {
        mockMvc.perform(post("/admin/factories")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"새 공장\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(factoryService);
    }

    @Test
    @WithMockUser(roles = "SYSTEM_ADMIN")
    void approve_user_with_system_admin_is_allowed() throws Exception {
        mockMvc.perform(patch("/admin/users/1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\",\"factoryId\":1,\"zoneIds\":[]}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "SYSTEM_ADMIN")
    void approve_user_patch_with_public_origin_is_allowed() throws Exception {
        mockMvc.perform(patch("/admin/users/1/approve")
                        .header(HttpHeaders.ORIGIN, "https://sensor.bugihub.site")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\",\"factoryId\":1,\"zoneIds\":[1,2]}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "SYSTEM_ADMIN")
    void reject_user_patch_with_public_origin_is_allowed() throws Exception {
        mockMvc.perform(patch("/admin/users/1/reject")
                        .header(HttpHeaders.ORIGIN, "https://sensor.bugihub.site"))
                .andExpect(status().isOk());
    }

    @Test
    void approve_user_patch_preflight_without_auth_is_allowed() throws Exception {
        mockMvc.perform(options("/admin/users/1/approve")
                        .header(HttpHeaders.ORIGIN, "https://sensor.bugihub.site")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "PATCH")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        "https://sensor.bugihub.site"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS,
                        org.hamcrest.Matchers.containsString("PATCH")));
    }

    @Test
    @WithMockUser(roles = "FACTORY_ADMIN")
    void approve_user_with_factory_admin_is_allowed() throws Exception {
        mockMvc.perform(patch("/admin/users/1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\",\"factoryId\":1,\"zoneIds\":[]}"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "SYSTEM_ADMIN")
    void approve_user_without_factory_returns_400() throws Exception {
        mockMvc.perform(patch("/admin/users/1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\",\"zoneIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
        verifyNoInteractions(adminService);
    }

    @Test
    @WithMockUser(roles = "SYSTEM_ADMIN")
    void approve_user_with_non_positive_factory_returns_400() throws Exception {
        mockMvc.perform(patch("/admin/users/1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\",\"factoryId\":0,\"zoneIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("BAD_REQUEST"));
        verifyNoInteractions(adminService);
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void approve_user_with_member_is_forbidden() throws Exception {
        mockMvc.perform(patch("/admin/users/1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\",\"factoryId\":1,\"zoneIds\":[]}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(adminService);
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void approve_user_with_viewer_is_forbidden() throws Exception {
        mockMvc.perform(patch("/admin/users/1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\",\"factoryId\":1,\"zoneIds\":[]}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(adminService);
    }

    @Test
    void approve_user_without_auth_is_unauthorized() throws Exception {
        mockMvc.perform(patch("/admin/users/1/approve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\",\"factoryId\":1,\"zoneIds\":[]}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(adminService);
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void get_channels_with_viewer_ok() throws Exception {
        mockMvc.perform(get("/channels"))
                .andExpect(status().is(not(403)));
    }

    @Test
    @WithMockUser(roles = "FACTORY_ADMIN")
    void get_channels_factory_admin_ok() throws Exception {
        mockMvc.perform(get("/channels"))
                .andExpect(status().is(not(403)));
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void put_channel_member_ok() throws Exception {
        mockMvc.perform(put("/channels/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().is(not(403)));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void put_channel_viewer_forbidden() throws Exception {
        mockMvc.perform(put("/channels/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SYSTEM_VIEWER")
    void put_channel_system_viewer_forbidden() throws Exception {
        mockMvc.perform(put("/channels/1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(channelService);
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void post_device_channel_member_ok() throws Exception {
        mockMvc.perform(post("/devices/1/channels")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"s99\"}"))
                .andExpect(status().is(not(403)));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void get_sensor_data_with_viewer() throws Exception {
        mockMvc.perform(get("/sensor-data"))
                .andExpect(status().is(not(403)));
    }

    @Test
    @WithMockUser(roles = "SYSTEM_VIEWER")
    void get_sensor_data_system_viewer_ok() throws Exception {
        mockMvc.perform(get("/sensor-data"))
                .andExpect(status().is(not(403)));
    }

    @Test
    @WithMockUser(roles = "FACTORY_ADMIN")
    void get_sensor_data_factory_admin_ok() throws Exception {
        mockMvc.perform(get("/sensor-data"))
                .andExpect(status().is(not(403)));
    }

    @Test
    @WithMockUser(roles = "FACTORY_ADMIN")
    void get_alerts_factory_admin_ok() throws Exception {
        mockMvc.perform(get("/alerts"))
                .andExpect(status().is(not(403)));
    }

    @Test
    void get_alarm_episodes_no_auth_is_unauthorized() throws Exception {
        mockMvc.perform(get("/alarm-episodes"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(alarmEpisodeService);
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void get_alarm_episodes_viewer_is_allowed() throws Exception {
        mockMvc.perform(get("/alarm-episodes"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "SYSTEM_VIEWER")
    void get_alarm_episodes_system_viewer_is_allowed() throws Exception {
        mockMvc.perform(get("/alarm-episodes"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = "MEMBER1", roles = "MEMBER")
    void get_alarm_episodes_maps_all_optional_filters() throws Exception {
        given(alarmEpisodeService.getEpisodes(
                org.mockito.ArgumentMatchers.eq("MEMBER1"),
                org.mockito.ArgumentMatchers.eq(
                        dev.bugi.sensor.alert.entity.AlarmEpisodeStatus.OPEN),
                org.mockito.ArgumentMatchers.eq(
                        dev.bugi.sensor.alert.entity.AlarmType.THRESHOLD),
                org.mockito.ArgumentMatchers.eq(
                        dev.bugi.sensor.alert.entity.AlarmScopeType.CHANNEL),
                org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.eq(11L),
                org.mockito.ArgumentMatchers.eq(3L),
                any(org.springframework.data.domain.Pageable.class)))
                .willReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get("/alarm-episodes")
                        .param("status", "OPEN")
                        .param("type", "THRESHOLD")
                        .param("scopeType", "CHANNEL")
                        .param("deviceId", "7")
                        .param("channelId", "11")
                        .param("zoneId", "3"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void ack_alarm_episode_member_is_allowed() throws Exception {
        mockMvc.perform(post("/alarm-episodes/1/ack"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "FACTORY_ADMIN")
    void ack_alarm_episode_factory_admin_is_allowed() throws Exception {
        mockMvc.perform(post("/alarm-episodes/1/ack"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "SYSTEM_ADMIN")
    void ack_alarm_episode_system_admin_is_allowed() throws Exception {
        mockMvc.perform(post("/alarm-episodes/1/ack"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void ack_alarm_episode_viewer_is_forbidden() throws Exception {
        mockMvc.perform(post("/alarm-episodes/1/ack"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(alarmEpisodeService);
    }

    @Test
    @WithMockUser(roles = "SYSTEM_VIEWER")
    void ack_alarm_episode_system_viewer_is_forbidden() throws Exception {
        mockMvc.perform(post("/alarm-episodes/1/ack"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(alarmEpisodeService);
    }

    @Test
    void get_zones_no_auth() throws Exception {
        mockMvc.perform(get("/zones"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = {"SYSTEM_ADMIN"})
    void get_zones_system_admin_ok() throws Exception {
        mockMvc.perform(get("/zones"))
                .andExpect(status().is(not(403)));
    }

    @Test
    @WithMockUser(roles = "FACTORY_ADMIN")
    void get_zones_factory_admin_ok() throws Exception {
        mockMvc.perform(get("/zones"))
                .andExpect(status().is(not(403)));
    }

    @Test
    @WithMockUser(roles = "MEMBER")
    void get_zones_member_ok() throws Exception {
        mockMvc.perform(get("/zones"))
                .andExpect(status().is(not(403)));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void get_zones_viewer_ok() throws Exception {
        mockMvc.perform(get("/zones"))
                .andExpect(status().is(not(403)));
    }

    @Test
    @WithMockUser(roles = "FACTORY_ADMIN")
    void get_admin_zones_org_admin_ok() throws Exception {
        mockMvc.perform(get("/admin/zones"))
                .andExpect(status().is(not(403)));
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void get_admin_zones_viewer_forbidden() throws Exception {
        mockMvc.perform(get("/admin/zones"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "SYSTEM_VIEWER")
    void get_admin_users_system_viewer_forbidden() throws Exception {
        mockMvc.perform(get("/admin/users"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(adminService);
    }

    private BatchIngestResult result(BatchIngestResult.Outcome outcome) {
        Instant now = Instant.parse("2026-07-19T00:00:00Z");
        BatchIngestResponse response = new BatchIngestResponse(
                outcome == BatchIngestResult.Outcome.SAVED ? 1L : null,
                outcome == BatchIngestResult.Outcome.DEVICE_NOT_FOUND ? null : 1L,
                "CMAPSS-U1",
                outcome == BatchIngestResult.Outcome.DEVICE_NOT_FOUND ? null : now,
                now,
                outcome == BatchIngestResult.Outcome.SAVED ? 1 : 0,
                List.of()
        );
        return new BatchIngestResult(outcome, response);
    }
}
