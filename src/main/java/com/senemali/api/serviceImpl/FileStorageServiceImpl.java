package com.senemali.api.serviceImpl;

import com.senemali.api.service.FileStorageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.*;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Implémentation du service de stockage des fichiers.
 * Stocke les fichiers dans le dossier local {app.upload.dir}/
 *
 * Structure des dossiers :
 *   uploads/
 *     profils/       → photos de profil utilisateurs
 *     produits/      → images produits agricoles
 *     intrants/      → images intrants
 *     tutoriels/     → images/vidéos tutoriels
 */
@Service
@Slf4j
public class FileStorageServiceImpl implements FileStorageService {

    @Value("${app.storage.provider:local}")
    private String storageProvider;

    @Value("${app.upload.dir:uploads}")
    private String uploadDir;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;

    @Value("${supabase.url:}")
    private String supabaseUrl;

    @Value("${supabase.service-role-key:}")
    private String supabaseServiceRoleKey;

    @Value("${supabase.bucket:senemali-uploads}")
    private String supabaseBucket;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final List<String> ALLOWED_IMAGE_EXTENSIONS = Arrays.asList(
            "jpg", "jpeg", "png", "gif", "webp", "svg"
    );

    private static final List<String> ALLOWED_VIDEO_EXTENSIONS = Arrays.asList("mp4", "webm", "mov");

    @Value("${app.upload.max-size:10485760}")
    private long maxImageSize;

    @Value("${app.upload.max-video-size:104857600}")
    private long maxVideoSize;

    @Override
    public String saveFile(MultipartFile file, String subFolder) {
        if (file == null || file.isEmpty()) {
            throw new RuntimeException("Le fichier est vide");
        }
        String originalFilename = StringUtils.cleanPath(
                file.getOriginalFilename() != null ? file.getOriginalFilename() : "file"
        );
        String extension = getExtension(originalFilename).toLowerCase();
        boolean video = "tutoriels".equals(subFolder) && ALLOWED_VIDEO_EXTENSIONS.contains(extension);
        if (!ALLOWED_IMAGE_EXTENSIONS.contains(extension) && !video) {
            List<String> allowedExtensions = "tutoriels".equals(subFolder)
                    ? Arrays.asList("jpg", "jpeg", "png", "gif", "webp", "svg", "mp4", "webm", "mov")
                    : ALLOWED_IMAGE_EXTENSIONS;
            throw new RuntimeException("Format de fichier non supporté. Utilisez: " + allowedExtensions);
        }
        long maxFileSize = video ? maxVideoSize : maxImageSize;
        if (file.getSize() > maxFileSize) {
            long maxSizeMb = maxFileSize / (1024 * 1024);
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    "Fichier trop volumineux (maximum " + maxSizeMb + " MB)"
            );
        }

        // Générer un nom unique pour éviter les conflits
        String newFileName = UUID.randomUUID() + "." + extension;

        if ("supabase".equalsIgnoreCase(storageProvider)) {
            uploadToSupabase(file, subFolder, newFileName);
            return newFileName;
        }
        if (!"local".equalsIgnoreCase(storageProvider)) {
            throw new IllegalStateException("Fournisseur de stockage inconnu: " + storageProvider);
        }

        try {
            Path targetDir = Paths.get(uploadDir, subFolder).toAbsolutePath().normalize();
            Files.createDirectories(targetDir);

            Path targetPath = targetDir.resolve(newFileName);
            Files.copy(file.getInputStream(), targetPath, StandardCopyOption.REPLACE_EXISTING);

            log.info("Fichier sauvegardé : {}/{}", subFolder, newFileName);
            return newFileName;
        } catch (IOException e) {
            log.error("Erreur lors de la sauvegarde du fichier", e);
            throw new RuntimeException("Impossible de sauvegarder le fichier: " + e.getMessage());
        }
    }

    @Override
    public void deleteFile(String fileName, String subFolder) {
        if (fileName == null || fileName.isBlank()) return;
        validateStoragePath(fileName, subFolder);
        if ("supabase".equalsIgnoreCase(storageProvider)) {
            deleteFromSupabase(fileName, subFolder);
            return;
        }
        if (!"local".equalsIgnoreCase(storageProvider)) {
            throw new IllegalStateException("Fournisseur de stockage inconnu: " + storageProvider);
        }
        try {
            Path filePath = Paths.get(uploadDir, subFolder, fileName).toAbsolutePath().normalize();
            Files.deleteIfExists(filePath);
            log.info("Fichier supprimé : {}/{}", subFolder, fileName);
        } catch (IOException e) {
            log.warn("Impossible de supprimer le fichier {}/{}: {}", subFolder, fileName, e.getMessage());
        }
    }

    @Override
    public String getFileUrl(String fileName, String subFolder) {
        if (fileName == null || fileName.isBlank()) return null;
        if ("supabase".equalsIgnoreCase(storageProvider)) {
            requireSupabaseConfiguration();
            return supabaseUrl.replaceAll("/+$", "") + "/storage/v1/object/public/"
                    + supabaseBucket + "/" + subFolder + "/" + fileName;
        }
        return baseUrl + "/uploads/" + subFolder + "/" + fileName;
    }

    private void uploadToSupabase(MultipartFile file, String subFolder, String fileName) {
        requireSupabaseConfiguration();
        URI uri = supabaseObjectUri(subFolder + "/" + fileName);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Bearer " + supabaseServiceRoleKey)
                .header("apikey", supabaseServiceRoleKey)
                .header("Content-Type", contentTypeFor(fileName))
                .header("x-upsert", "true")
                .POST(HttpRequest.BodyPublishers.ofByteArray(readFileBytes(file)))
                .build();
        sendSupabaseRequest(request, "upload");
        log.info("Fichier envoyé vers Supabase Storage : {}/{}", subFolder, fileName);
    }

    private void deleteFromSupabase(String fileName, String subFolder) {
        requireSupabaseConfiguration();
        String objectPath = subFolder + "/" + fileName;
        String body = "{\"prefixes\":[\"" + objectPath + "\"]}";
        HttpRequest request = HttpRequest.newBuilder(supabaseObjectUri(""))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + supabaseServiceRoleKey)
                .header("apikey", supabaseServiceRoleKey)
                .header("Content-Type", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(body))
                .build();
        sendSupabaseRequest(request, "suppression");
        log.info("Fichier supprimé de Supabase Storage : {}", objectPath);
    }

    private void sendSupabaseRequest(HttpRequest request, String operation) {
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.error("Supabase Storage a refusé l'opération {} (HTTP {})",
                        operation, response.statusCode());
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Échec de l'opération de stockage distant"
                );
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Opération de stockage distant interrompue",
                    e
            );
        } catch (IOException e) {
            log.error("Impossible de joindre Supabase Storage pour l'opération {}", operation, e);
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Service de stockage distant indisponible",
                    e
            );
        }
    }

    private byte[] readFileBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Impossible de lire le fichier envoyé", e);
        }
    }

    private URI supabaseObjectUri(String objectPath) {
        String base = supabaseUrl.replaceAll("/+$", "");
        String path = objectPath.isEmpty() ? "" : "/" + objectPath;
        return URI.create(base + "/storage/v1/object/" + supabaseBucket + path);
    }

    private String contentTypeFor(String fileName) {
        String extension = getExtension(fileName).toLowerCase();
        return switch (extension) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            case "webp" -> "image/webp";
            case "svg" -> "image/svg+xml";
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            case "mov" -> "video/quicktime";
            default -> "application/octet-stream";
        };
    }

    private void requireSupabaseConfiguration() {
        if (supabaseUrl == null || supabaseUrl.isBlank()
                || supabaseServiceRoleKey == null || supabaseServiceRoleKey.isBlank()
                || supabaseBucket == null || supabaseBucket.isBlank()) {
            throw new IllegalStateException(
                    "SUPABASE_URL, SUPABASE_SERVICE_ROLE_KEY et SUPABASE_BUCKET doivent être configurés"
            );
        }
    }

    private void validateStoragePath(String fileName, String subFolder) {
        if (fileName.contains("/") || fileName.contains("\\") || fileName.equals(".") || fileName.equals("..")) {
            throw new IllegalArgumentException("Nom de fichier invalide");
        }
        if (subFolder == null || !subFolder.matches(
                "profils|produits|intrants|cultures|tutoriels|services|conseils|financements")) {
            throw new IllegalArgumentException("Dossier de stockage invalide");
        }
    }

    private String getExtension(String filename) {
        int dotIndex = filename.lastIndexOf('.');
        return dotIndex > 0 ? filename.substring(dotIndex + 1) : "";
    }
}
