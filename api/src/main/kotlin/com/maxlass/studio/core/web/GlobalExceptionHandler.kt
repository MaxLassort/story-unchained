package com.maxlass.studio.core.web

import com.maxlass.studio.core.api.ApiStatusResponse
import com.maxlass.studio.pack.service.DraftIncompleteException
import com.maxlass.studio.pack.service.TtsApiKeyMissingException
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.server.ResponseStatusException
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

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun handleMissingParam(e: MissingServletRequestParameterException): ResponseEntity<ApiStatusResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiStatusResponse(ok = false, error = "Required request parameter '${e.parameterName}' is not present"))

    @ExceptionHandler(HandlerMethodValidationException::class)
    fun handleValidation(e: HandlerMethodValidationException): ResponseEntity<ApiStatusResponse> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST)
            .body(ApiStatusResponse(
                ok = false,
                error = e.parameterValidationResults
                    .flatMap { it.resolvableErrors }
                    .joinToString("; ") { it.defaultMessage ?: "Invalid parameter" },
            ))

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatus(e: ResponseStatusException): ResponseEntity<ApiStatusResponse> =
        ResponseEntity.status(e.statusCode)
            .body(ApiStatusResponse(ok = false, error = e.reason ?: "HTTP ${e.statusCode.value()}"))

    @ExceptionHandler(TtsApiKeyMissingException::class)
    fun handleTtsApiKeyMissing(e: TtsApiKeyMissingException): ResponseEntity<ApiStatusResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiStatusResponse(ok = false, error = e.message ?: "TTS API key missing"))

    @ExceptionHandler(DraftIncompleteException::class)
    fun handleDraftIncomplete(e: DraftIncompleteException): ResponseEntity<ApiStatusResponse> =
        ResponseEntity.status(HttpStatus.CONFLICT)
            .body(ApiStatusResponse(ok = false, error = e.message ?: "Draft incomplete"))

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(e: NoSuchElementException): ResponseEntity<ApiStatusResponse> =
        ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(ApiStatusResponse(ok = false, error = e.message ?: "Not found"))

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
