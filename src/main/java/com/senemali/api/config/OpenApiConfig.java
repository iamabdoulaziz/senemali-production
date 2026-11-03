package com.senemali.api.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.info.License;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration OpenAPI / Swagger pour la documentation de l'API Senemali.
 * Accessible via : http://localhost:8080/swagger-ui.html
 *
 * Cette API est consommée par :
 *  - Angular (application web d'administration et marketplace)
 *  - Flutter (application mobile iOS/Android pour agriculteurs et acheteurs)
 */
@Configuration
@OpenAPIDefinition(
        info = @Info(
                title = "Senemali API",
                version = "1.0.0",
                description = """
                        ## API REST pour la plateforme agricole Senemali - Mali
                        
                        ### Clients supportés
                        - **Angular** : Application web (admin, marketplace)
                        - **Flutter** : Application mobile iOS/Android
                        
                        ### Authentification
                        Utiliser le endpoint `/api/auth/login` pour obtenir un token JWT.  
                        Inclure le token dans le header : `Authorization: Bearer <token>`
                        
                        ### Rôles disponibles
                        - `ADMIN` : Accès complet
                        - `AGRICULTEUR` : Gestion de ses produits, cultures
                        - `ACHETEUR` : Commandes, achats
                        - `FOURNISSEUR` : Gestion des intrants, services
                        """,
                contact = @Contact(
                        name = "Senemali Team",
                        email = "contact@senemali.ml",
                        url = "https://senemali.ml"
                ),
                license = @License(name = "Propriétaire")
        ),
        servers = {
                @Server(url = "http://localhost:8080", description = "Serveur de développement local"),
                @Server(url = "https://senemali-production.onrender.com/", description = "Serveur de production")
        }
)
@SecurityScheme(
        name = "bearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "Token JWT obtenu via /api/auth/login"
)
public class OpenApiConfig {
}
