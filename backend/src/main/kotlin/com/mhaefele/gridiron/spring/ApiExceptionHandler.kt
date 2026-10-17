package com.mhaefele.gridiron.spring

import com.mhaefele.gridiron.api.MatchNotFoundException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.async.AsyncRequestNotUsableException
import org.springframework.web.server.ResponseStatusException

@RestControllerAdvice
class ApiExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(MatchNotFoundException::class)
    fun matchNotFound(ex: MatchNotFoundException): ResponseEntity<ProblemDetail> {
        log.warn("Match not found, ${ex.message}")
        return problem(HttpStatus.NOT_FOUND, ex.message)
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun methodArgumentNotValid(ex: MethodArgumentNotValidException): ResponseEntity<ProblemDetail> {
        val detail = ex.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" }
        log.warn(ex.javaClass.name, ex)
        return problem(HttpStatus.BAD_REQUEST, detail)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun badRequest(ex: IllegalArgumentException): ResponseEntity<ProblemDetail> {
        log.warn(ex.javaClass.name, ex)
        return problem(HttpStatus.BAD_REQUEST, ex.message)
    }

    @ExceptionHandler(IllegalStateException::class)
    fun internalError(ex: IllegalStateException): ResponseEntity<ProblemDetail> {
        log.error(ex.javaClass.name, ex)
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ex.message)
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun responseStatus(ex: ResponseStatusException): ResponseEntity<ProblemDetail> {
        val status = HttpStatus.resolve(ex.statusCode.value()) ?: HttpStatus.INTERNAL_SERVER_ERROR
        if (status.is5xxServerError) {
            log.error(ex.javaClass.name, ex)
        } else {
            log.warn(ex.javaClass.name, ex)
        }
        return problem(status, ex.reason ?: ex.message)
    }

    /**
     * Servlet containers report a closed async response as their own [java.io.IOException]
     * subtype. Spring wraps that as [AsyncRequestNotUsableException] on the async error
     * callback before re-dispatch. The SSE response is already `text/event-stream`, so
     * there is no body to write.
     */
    @ExceptionHandler(AsyncRequestNotUsableException::class)
    fun clientDisconnected(ex: AsyncRequestNotUsableException) {
        log.warn("Client disconnected: {}", ex.toString())
    }

    @ExceptionHandler(Exception::class)
    fun unhandled(ex: Exception): ResponseEntity<ProblemDetail> {
        log.error(ex.javaClass.name, ex)
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, ex.message)
    }

    private fun problem(status: HttpStatus, detail: String?): ResponseEntity<ProblemDetail> {
        val text = detail?.takeIf { it.isNotBlank() } ?: status.reasonPhrase
        return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, text))
    }
}
