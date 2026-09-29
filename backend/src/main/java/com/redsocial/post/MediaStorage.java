package com.redsocial.post;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ServiceUnavailableException;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.time.Duration;
import java.util.Iterator;
import java.util.Locale;
import java.util.UUID;

@ApplicationScoped
public class MediaStorage {

    static final long MAX_BYTES = 5L * 1024 * 1024;
    private static final long MAX_PIXELS = 16_000_000L;

    @Inject S3Client s3;
    @ConfigProperty(name = "app.minio.bucket") String bucket;
    @ConfigProperty(name = "app.minio.public-endpoint") String publicEndpoint;
    @ConfigProperty(name = "quarkus.s3.aws.region") String region;
    @ConfigProperty(name = "quarkus.s3.aws.credentials.static-provider.access-key-id") String accessKey;
    @ConfigProperty(name = "quarkus.s3.aws.credentials.static-provider.secret-access-key") String secretKey;

    public MediaAsset upload(FileUpload file, String prefix) {
        MediaAsset asset = inspect(file, prefix);
        try {
            s3.putObject(PutObjectRequest.builder()
                    .bucket(bucket).key(asset.key()).contentType(asset.contentType())
                    .contentLength(asset.size()).build(), RequestBody.fromFile(file.filePath()));
            return asset;
        } catch (RuntimeException ex) {
            throw new ServiceUnavailableException("Media storage unavailable", 5L);
        }
    }

    MediaAsset inspect(FileUpload file, String prefix) {
        if (file == null || file.fileName() == null || file.fileName().isBlank()) {
            throw new BadRequestException("One image is required");
        }
        String name = file.fileName().replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).toLowerCase(Locale.ROOT);
        boolean png = name.endsWith(".png") && "image/png".equals(file.contentType());
        boolean jpeg = (name.endsWith(".jpg") || name.endsWith(".jpeg"))
                && "image/jpeg".equals(file.contentType());
        if (!png && !jpeg) throw new BadRequestException("Only PNG and JPEG images are allowed");

        try {
            long size = Files.size(file.filePath());
            if (size == 0 || size > MAX_BYTES) throw new BadRequestException("Image must be 1 byte to 5 MiB");
            try (ImageInputStream input = ImageIO.createImageInputStream(file.filePath().toFile())) {
                if (input == null) throw new BadRequestException("Invalid image");
                Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new BadRequestException("Invalid image");
                ImageReader reader = readers.next();
                try {
                    reader.setInput(input);
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    if (!(png && format.equals("png")) && !(jpeg && (format.equals("jpeg") || format.equals("jpg")))) {
                        throw new BadRequestException("Image type does not match its contents");
                    }
                    long pixels = (long) reader.getWidth(0) * reader.getHeight(0);
                    if (pixels == 0 || pixels > MAX_PIXELS) throw new BadRequestException("Image dimensions are too large");
                    if (reader.read(0) == null) throw new BadRequestException("Invalid image");
                } finally {
                    reader.dispose();
                }
            }
            String extension = png ? ".png" : ".jpg";
            return new MediaAsset(prefix + "/" + UUID.randomUUID() + extension, file.contentType(), size);
        } catch (IOException | RuntimeException ex) {
            if (ex instanceof BadRequestException badRequest) throw badRequest;
            throw new BadRequestException("Invalid image");
        }
    }

    public String presignedReadUrl(String key) {
        try (S3Presigner presigner = S3Presigner.builder()
                .region(Region.of(region))
                .endpointOverride(URI.create(publicEndpoint))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()) {
            return presigner.presignGetObject(GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofSeconds(60))
                    .getObjectRequest(GetObjectRequest.builder().bucket(bucket).key(key).build())
                    .build()).url().toString();
        }
    }

    public String publicUrl(String key) {
        return publicEndpoint + "/" + bucket + "/" + key;
    }

    public void delete(String key) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build());
    }
}
