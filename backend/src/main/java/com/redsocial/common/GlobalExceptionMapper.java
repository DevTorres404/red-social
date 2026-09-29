package com.redsocial.common;

import io.vertx.core.http.HttpServerRequest;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.jboss.logging.Logger;

/**
 * Global exception → HTTP response mapper.
 *
 * Instead of letting Quarkus return a generic 500 with a stacktrace,
 * this maps well-known exceptions to proper HTTP status codes and a
 * uniform JSON body ({status, error, message, path, timestamp}).
 */
@Provider
public class GlobalExceptionMapper implements ExceptionMapper<Exception> {

    private static final Logger LOG = Logger.getLogger(GlobalExceptionMapper.class);

    @Context
    UriInfo uriInfo;

    /** Sólo para el log: UriInfo no expone el verbo HTTP. */
    @Context
    HttpServerRequest httpRequest;

    @Override
    public Response toResponse(Exception exception) {
        String path = uriInfo != null ? uriInfo.getPath() : "";

        if (exception instanceof BadRequestException ex) {
            return build(400, "Bad Request", ex.getMessage(), path);
        }
        if (exception instanceof NotAuthorizedException) {
            return build(401, "Unauthorized", "Invalid credentials", path);
        }
        if (exception instanceof NotFoundException ex) {
            return build(404, "Not Found", ex.getMessage(), path);
        }
        if (exception instanceof jakarta.validation.ConstraintViolationException ex) {
            String msg = ex.getConstraintViolations().stream()
                    .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("Validation failed");
            return build(422, "Unprocessable Entity", msg, path);
        }

        // Cualquier otra WebApplicationException ya trae el status correcto
        // (405 método no permitido, 415 media type no soportado, el 400 que
        // lanza el reader de Jackson con un body malformado...). Delegar en el
        // status propio evita inventar un 500 donde no hubo falla del server.
        // Tiene que ir DESPUÉS de las ramas de arriba: NotAuthorizedException
        // también es WebApplicationException y su body 401 ya es contrato.
        if (exception instanceof WebApplicationException ex) {
            int status = statusOf(ex);
            String reason = reasonPhrase(status);
            if (status >= 400 && status < 600 && reason != null) {
                if (status >= 500) {
                    LOG.errorf(exception, "Unhandled exception on %s %s", uriMethod(), path);
                    return build(status, reason, "An unexpected error occurred", path);
                }
                // 4xx: input inválido de rutina (body malformado, verbo
                // incorrecto), no un incidente. Un stack trace acá inunda el log.
                LOG.debugf("Protocol error %d (%s) on %s %s", status, reason, uriMethod(), path);
                String msg = ex.getMessage() != null ? ex.getMessage() : reason;
                return build(status, reason, msg, path);
            }
            // Status ausente o inválido: cae al 500 de abajo.
        }

        // Fallback: la respuesta es genérica a propósito (no se filtran
        // internals al cliente), así que este log es el ÚNICO diagnóstico que
        // existe para estas fallas.
        LOG.errorf(exception, "Unhandled exception on %s %s", uriMethod(), path);
        return build(500, "Internal Server Error", "An unexpected error occurred", path);
    }

    /**
     * El status que la propia excepción declara, o 0 si no lo declara.
     * Jackson y el router de Quarkus pueden lanzar la excepción sin response
     * o con una response vacía, así que nunca se desreferencia sin chequear.
     */
    private int statusOf(WebApplicationException ex) {
        Response response = ex.getResponse();
        return response != null ? response.getStatus() : 0;
    }

    /**
     * La razón estándar de un status HTTP, o null si el código no corresponde
     * a un status válido. Derivar el texto del código evita hardcodear status.
     */
    private String reasonPhrase(int status) {
        try {
            Response.Status known = Response.Status.fromStatusCode(status);
            return known != null ? known.getReasonPhrase() : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * El verbo HTTP del request, o "?" si no está disponible.
     * UriInfo no lo expone; el contexto de Vert.x sí.
     */
    private String uriMethod() {
        return httpRequest != null && httpRequest.method() != null
                ? httpRequest.method().name()
                : "?";
    }

    private Response build(int status, String error, String message, String path) {
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(ApiError.of(status, error, message, path))
                .build();
    }
}
