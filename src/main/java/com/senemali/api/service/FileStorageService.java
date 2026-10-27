package com.senemali.api.service;

import org.springframework.web.multipart.MultipartFile;

/**
 * Service de gestion des fichiers uploadés localement ou dans Supabase Storage.
 * Utilisé par Angular et Flutter pour uploader des images.
 */
public interface FileStorageService {

    /**
     * Sauvegarde un fichier dans le stockage configuré
     * @param file le fichier multipart
     * @param subFolder sous-dossier (ex: "profils", "produits")
     * @return le nom du fichier sauvegardé
     */
    String saveFile(MultipartFile file, String subFolder);

    /**
     * Supprime un fichier du stockage configuré
     * @param fileName nom du fichier
     * @param subFolder sous-dossier
     */
    void deleteFile(String fileName, String subFolder);

    /**
     * Retourne l'URL publique d'un fichier
     * @param fileName nom du fichier
     * @param subFolder sous-dossier
     * @return URL publique complète du fichier
     */
    String getFileUrl(String fileName, String subFolder);
}
