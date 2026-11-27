package com.supportforge.api;

import com.supportforge.api.error.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;

class HttpStatusExceptionTest {
    @Test
    void explicitNotFoundKeeps404AndServerFailuresDoNotExposeInternalDetails() {
        var handler = new GlobalExceptionHandler();
        var request = new MockHttpServletRequest("GET", "/knowledge/enterprise/traces/missing");
        var notFound = handler.handleUnexpected(new ResponseStatusException(HttpStatus.NOT_FOUND, "检索轨迹不存在"), request);
        assertThat(notFound.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(notFound.getBody().message()).isEqualTo("检索轨迹不存在");
        var unavailable = handler.handleUnexpected(new ResponseStatusException(
                HttpStatus.SERVICE_UNAVAILABLE, "internal credentials must not leak"), request);
        assertThat(unavailable.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(unavailable.getBody().message()).doesNotContain("credentials");
    }
}
