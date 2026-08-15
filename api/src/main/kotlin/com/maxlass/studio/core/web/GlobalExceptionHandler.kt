package com.maxlass.studio.core.web

import com.maxlass.studio.core.api.ApiStatusResponse
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * Global exception handler (parité avec le StatusPages Ktor) : toute exception non gérée
 * devient un 500 avec `ApiStatusResponse(ok=false, error=...)`.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    companion object {
        private val logger = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)
    }

    @ExceptionHandler(NoResourceFoundException::class)
    fun handleNotFound(e: NoResourceFoundException): ResponseEntity<ApiStatusResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiStatusResponse(ok = false, error = "Resource not found: ${e.resourcePath}"))

    @ExceptionHandler(Exception::class)
    fun handleException(e: Exception, response: HttpServletResponse): ResponseEntity<ApiStatusResponse>? {
        // For streamed responses (SSE), the response is already committed and its
        // Content-Type cannot be replaced, so writing a JSON error body would fail.
        if (response.isCommitted) {
            logger.error("Exception after response committed; closing connection", e)
            return null
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ApiStatusResponse(ok = false, error = e.message ?: "Internal server error"))
    }
}
