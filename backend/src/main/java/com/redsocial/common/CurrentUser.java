package com.redsocial.common;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotAuthorizedException;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Acceso al usuario autenticado: el id del claim "sub" del JWT.
 *
 * ADVERTENCIA — no usar SecurityContext.getUserPrincipal().getName() para
 * obtener el id del usuario. Ese método devuelve getName() del principal JWT, y
 * SmallRye resuelve getName() con el claim "upn" (el username, p.ej. "admin"),
 * nunca con "sub" (el id, p.ej. "4ba993fc-ac18-4ca4-bc59-d214fdbf525b").
 *
 * Los repositorios hacen MATCH (:Usuario {id: $userId}), así que pasar el
 * username no matcheaba nada: los reads devolvían listas vacías y los writes no
 * escribían nada, ambos con HTTP 200/204. Fallo silencioso con pérdida de datos.
 *
 * Esta clase es la única forma sancionada de obtener el id del usuario actual.
 */
@ApplicationScoped
public class CurrentUser {

    @Inject
    JsonWebToken jwt;

    /**
     * Id del usuario autenticado, leído del claim "sub" del JWT.
     *
     * El claim se lee por nombre y no vía getName(), porque getName() devuelve
     * "upn". Tampoco se usa getSubject(): leer "sub" explícito deja el origen
     * del valor a la vista y no depende de la semántica del accessor.
     *
     * @throws NotAuthorizedException (401) si el token no trae "sub". Antes de
     *         este guard los repositorios recibían un valor inútil y devolvían
     *         vacío en silencio; un 401 explícito es preferible a perder datos.
     */
    public String id() {
        String sub = jwt.getClaim("sub");
        if (sub == null || sub.isBlank()) {
            throw new NotAuthorizedException("Token without 'sub' claim");
        }
        return sub;
    }
}
