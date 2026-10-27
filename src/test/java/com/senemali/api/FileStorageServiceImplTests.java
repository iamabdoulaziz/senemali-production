package com.senemali.api;

import com.senemali.api.serviceImpl.FileStorageServiceImpl;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FileStorageServiceImplTests {
    private HttpServer supabaseMock;
    private final AtomicReference<String> requestMethod = new AtomicReference<>();
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> requestAuthorization = new AtomicReference<>();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private FileStorageServiceImpl fileStorageService;

    @BeforeEach
    void setUp() throws IOException {
        supabaseMock = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        supabaseMock.createContext("/", exchange -> {
            requestMethod.set(exchange.getRequestMethod());
            requestPath.set(exchange.getRequestURI().getPath());
            requestAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        supabaseMock.start();

        fileStorageService = new FileStorageServiceImpl();
        ReflectionTestUtils.setField(fileStorageService, "storageProvider", "supabase");
        ReflectionTestUtils.setField(fileStorageService, "supabaseUrl",
                "http://localhost:" + supabaseMock.getAddress().getPort());
        ReflectionTestUtils.setField(fileStorageService, "supabaseServiceRoleKey", "test-service-key");
        ReflectionTestUtils.setField(fileStorageService, "supabaseBucket", "senemali-uploads");
        ReflectionTestUtils.setField(fileStorageService, "maxImageSize", 10 * 1024 * 1024L);
        ReflectionTestUtils.setField(fileStorageService, "maxVideoSize", 100 * 1024 * 1024L);
    }

    @AfterEach
    void tearDown() {
        supabaseMock.stop(0);
    }

    @Test
    void uploadsFileAndReturnsSupabasePublicUrl() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", new byte[]{1, 2, 3});

        String fileName = fileStorageService.saveFile(file, "profils");

        assertEquals("POST", requestMethod.get());
        assertEquals("/storage/v1/object/senemali-uploads/profils/" + fileName, requestPath.get());
        assertEquals("Bearer test-service-key", requestAuthorization.get());
        assertEquals("http://localhost:" + supabaseMock.getAddress().getPort()
                + "/storage/v1/object/public/senemali-uploads/profils/" + fileName,
                fileStorageService.getFileUrl(fileName, "profils"));
    }

    @Test
    void deletesFileFromSupabaseStorage() {
        fileStorageService.deleteFile("image.jpg", "profils");

        assertEquals("DELETE", requestMethod.get());
        assertEquals("/storage/v1/object/senemali-uploads", requestPath.get());
        assertEquals("{\"prefixes\":[\"profils/image.jpg\"]}", requestBody.get());
    }
}
