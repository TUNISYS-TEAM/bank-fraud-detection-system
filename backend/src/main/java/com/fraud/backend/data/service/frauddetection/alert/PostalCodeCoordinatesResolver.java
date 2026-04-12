package com.fraud.backend.data.service.frauddetection.alert;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class PostalCodeCoordinatesResolver {

    private final ObjectMapper objectMapper;
    private final Map<String, double[]> zipToCoords = new HashMap<>();
    
    // Fallback coordinates (Tunis center)
    private static final double[] FALLBACK_COORDS = {36.8065, 10.1815};

    @PostConstruct
    public void init() {
        try (InputStream is = new ClassPathResource("geo/zip_coords.json").getInputStream()) {
            Map<String, Map<String, Double>> data = objectMapper.readValue(
                is,
                new TypeReference<Map<String, Map<String, Double>>>() {}
            );
            for (Map.Entry<String, Map<String, Double>> entry : data.entrySet()) {
                double lat = entry.getValue().get("lat");
                double lon = entry.getValue().get("lon");
                zipToCoords.put(entry.getKey(), new double[]{lat, lon});
            }
            log.info("Loaded coordinates for {} postal codes", zipToCoords.size());
        } catch (IOException e) {
            log.error("Failed to load geo/zip_coords.json. Using fallback coordinates for all postal codes.", e);
        }
    }

    /**
     * Returns [latitude, longitude] for a given postal code.
     * Normalizes to 4 digits and falls back to Tunis center if not found.
     */
    public double[] getCoordinates(String postalCode) {
        if (postalCode == null || postalCode.isBlank()) {
            return FALLBACK_COORDS;
        }
        // Normalize to 4 digits (e.g., "2036" stays "2036", "02036" -> "2036")
        String normalized = postalCode.trim().replaceFirst("^0+", "");
        if (normalized.length() < 4) {
            normalized = String.format("%4s", normalized).replace(' ', '0');
        }
        return zipToCoords.getOrDefault(normalized, FALLBACK_COORDS);
    }
}