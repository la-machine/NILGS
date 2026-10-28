// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.maps;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.snigf.mvp.identity.IdentityIntegrationTest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * ADR-029 through the endpoints: the technical view is always offered; the others only with
 * installed data, otherwise with the reason; archives are served as byte ranges to parcel
 * readers only; nothing outside the map data directory is reachable.
 */
class MapApiIT extends IdentityIntegrationTest {

    /** The test profile's map data directory ({@code application-test.yaml}). */
    static final Path DATA = Path.of("target/test-maps");

    @AfterEach
    void removeInstalledData() throws Exception {
        if (Files.exists(DATA)) {
            try (var paths = Files.walk(DATA)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void withoutDataOnlyTheTechnicalViewIsAvailableAndTheOthersSayWhy() throws Exception {
        SignedIn reviewer = signedIn("REVIEWER");
        JsonNode views = json.readTree(reviewer.browser().send(get("/api/v1/map/backgrounds")).getContentAsString());
        assertThat(views.size()).isEqualTo(3);
        assertThat(views.get(0).path("kind").asString("")).isEqualTo("TECHNICAL");
        assertThat(views.get(0).path("available").asBoolean()).isTrue();
        assertThat(views.get(1).path("unavailableReason").asString("")).isEqualTo("NOT_INSTALLED");
        assertThat(views.get(2).path("unavailableReason").asString("")).isEqualTo("NOT_INSTALLED");
        assertThat(reviewer.browser().send(get("/api/v1/map/satellite/tiles.pmtiles")).getStatus()).isEqualTo(404);
    }

    @Test
    void anInstalledArchiveIsServedAsByteRangesToParcelReadersOnly() throws Exception {
        Files.createDirectories(DATA.resolve("satellite"));
        Files.writeString(DATA.resolve("satellite/manifest.json"), """
                {"format":"snigf-map-manifest/1","kind":"satellite","name":"Synthetic imagery","file":"tiles.pmtiles",
                 "tileType":"raster","attribution":"SYNTHETIC TEST IMAGERY","license":"none (synthetic)","source":"generated",
                 "date":"2026-10-22","bounds":[8.0,1.5,16.5,13.5],"minzoom":0,"maxzoom":12,"synthetic":true}""");
        byte[] archive = new byte[4096];
        for (int i = 0; i < archive.length; i++) {
            archive[i] = (byte) i;
        }
        Files.write(DATA.resolve("satellite/tiles.pmtiles"), archive);

        SignedIn reviewer = signedIn("REVIEWER");
        JsonNode satellite = json.readTree(reviewer.browser().send(get("/api/v1/map/backgrounds")).getContentAsString()).get(2);
        assertThat(satellite.path("available").asBoolean()).isTrue();
        assertThat(satellite.path("synthetic").asBoolean()).isTrue();
        assertThat(satellite.path("tilesUrl").asString("")).isEqualTo("/api/v1/map/satellite/tiles.pmtiles");
        assertThat(satellite.path("attribution").asString("")).isEqualTo("SYNTHETIC TEST IMAGERY");

        MockHttpServletResponse range = reviewer.browser().send(get("/api/v1/map/satellite/tiles.pmtiles")
                .header("Range", "bytes=100-163"));
        assertThat(range.getStatus()).isEqualTo(206);
        assertThat(range.getContentAsByteArray()).hasSize(64).startsWith((byte) 100);
        assertThat(range.getHeader("Cache-Control")).contains("private");

        String email = email();
        Browser applicant = new Browser();
        applicant.register(email, "Applicant", PASSWORD);
        applicant.login(email, PASSWORD);
        assertThat(applicant.send(get("/api/v1/map/satellite/tiles.pmtiles")).getStatus()).isEqualTo(403);
        assertThat(applicant.send(get("/api/v1/map/backgrounds")).getStatus()).isEqualTo(403);
        assertThat(new Browser().send(get("/api/v1/map/backgrounds")).getStatus()).isEqualTo(401);
    }

    @Test
    void detailArchivesAreListedAndServedButNoOtherFile() throws Exception {
        Files.createDirectories(DATA.resolve("satellite"));
        Files.writeString(DATA.resolve("satellite/manifest.json"), """
                {"format":"snigf-map-manifest/1","kind":"satellite","name":"Sentinel-2 mosaic","file":"tiles.pmtiles",
                 "tileType":"raster","attribution":"Contains modified Copernicus Sentinel data 2026","license":"Copernicus open data",
                 "source":"Sentinel-2 L2A","date":"2026-04-20","bounds":[8.0,1.5,16.5,13.5],"minzoom":0,"maxzoom":14,
                 "synthetic":false,"details":[{"name":"Douala aerial","file":"detail-douala.pmtiles",
                 "attribution":"(c) OpenAerialMap contributors","license":"CC-BY 4.0","source":"OpenAerialMap",
                 "date":"2026-05-10","bounds":[9.72,3.97,9.77,4.01],"minzoom":14,"maxzoom":20}]}""");
        Files.write(DATA.resolve("satellite/tiles.pmtiles"), new byte[] {1, 2, 3});
        Files.write(DATA.resolve("satellite/detail-douala.pmtiles"), new byte[] {4, 5, 6});
        Files.write(DATA.resolve("satellite/other.pmtiles"), new byte[] {7});

        SignedIn reviewer = signedIn("REVIEWER");
        JsonNode satellite = json.readTree(reviewer.browser().send(get("/api/v1/map/backgrounds")).getContentAsString()).get(2);
        assertThat(satellite.path("synthetic").asBoolean()).isFalse();
        JsonNode detail = satellite.path("details").get(0);
        assertThat(detail.path("tilesUrl").asString("")).isEqualTo("/api/v1/map/satellite/detail-douala.pmtiles");
        assertThat(detail.path("attribution").asString("")).isEqualTo("(c) OpenAerialMap contributors");
        assertThat(detail.path("minzoom").asInt()).isEqualTo(14);

        assertThat(reviewer.browser().send(get("/api/v1/map/satellite/detail-douala.pmtiles")).getContentAsByteArray())
                .containsExactly(4, 5, 6);
        assertThat(reviewer.browser().send(get("/api/v1/map/satellite/other.pmtiles")).getStatus()).isEqualTo(404);
        assertThat(reviewer.browser().send(get("/api/v1/map/satellite/manifest.json")).getStatus()).isEqualTo(404);
    }

    @Test
    void pathsOutsideTheDataDirectoryAreNeverServed() throws Exception {
        SignedIn reviewer = signedIn("REVIEWER");
        assertThat(reviewer.browser().send(get("/api/v1/map/technical/tiles.pmtiles")).getStatus()).isEqualTo(404);
        assertThat(reviewer.browser().send(get("/api/v1/map/..%2F..%2Fsecrets/tiles.pmtiles")).getStatus())
                .isIn(400, 404);
        assertThat(reviewer.browser().send(get("/api/v1/map/geographic/fonts/..%2F..%2Fsatellite/0-255.pbf")).getStatus())
                .isIn(400, 404);
    }
}
