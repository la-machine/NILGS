// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.maps.infrastructure.web;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;

import org.snigf.mvp.maps.application.MapCatalogue;
import org.snigf.mvp.maps.domain.BackgroundKind;
import org.snigf.mvp.maps.domain.MapDataset;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Map backgrounds (ADR-029). Whoever may read parcels somewhere may see the backgrounds;
 * licensed imagery is therefore never public. Archives are served as byte ranges (HTTP Range,
 * as PMTiles readers ask), from our own disk only. No parcel data goes through here.
 */
@RestController
@RequestMapping("/api/v1/map")
class MapController {

    private static final MediaType PMTILES = MediaType.parseMediaType("application/vnd.pmtiles");
    private static final MediaType PROTOBUF = MediaType.parseMediaType("application/x-protobuf");

    private final MapCatalogue catalogue;

    MapController(MapCatalogue catalogue) {
        this.catalogue = catalogue;
    }

    /** A view: available with its data and URLs, or unavailable with the reason. */
    record BackgroundResponse(String kind, boolean available, String unavailableReason, String name, String tileType,
                              String tilesUrl, String glyphsUrl, String attribution, String license, String source,
                              String date, double[] bounds, Integer minzoom, Integer maxzoom, boolean synthetic,
                              List<DetailResponse> details) {
    }

    /** A sharper archive drawn over the base within its bounds and zoom range. */
    record DetailResponse(String name, String tilesUrl, String attribution, String license, String source, String date,
                          double[] bounds, int minzoom, int maxzoom) {
    }

    @GetMapping("/backgrounds")
    @PreAuthorize("@access.canSomewhere(authentication, 'parcel.read')")
    List<BackgroundResponse> backgrounds() {
        return catalogue.backgrounds().stream().map(b -> {
            MapDataset d = b.dataset();
            if (d == null) {
                return new BackgroundResponse(b.kind().name(), b.available(), b.unavailableReason(), null, null, null,
                        null, null, null, null, null, null, null, null, false, List.of());
            }
            String base = "/api/v1/map/" + b.kind().directory();
            return new BackgroundResponse(b.kind().name(), true, null, d.name(), d.tileType(), base + "/tiles.pmtiles",
                    b.kind() == BackgroundKind.GEOGRAPHIC ? base + "/fonts/{fontstack}/{range}.pbf" : null,
                    d.attribution(), d.license(), d.source(), d.date(), d.bounds(), d.minzoom(), d.maxzoom(),
                    d.synthetic(), d.details().stream().map(x -> new DetailResponse(x.name(), base + "/" + x.file(),
                            x.attribution(), x.license(), x.source(), x.date(), x.bounds(), x.minzoom(), x.maxzoom()))
                    .toList());
        }).toList();
    }

    /**
     * The base or a detail archive, whole or as the requested byte ranges (206), privately
     * cacheable. Only a file the installed manifest lists is served; any other name is 404.
     */
    @GetMapping("/{kind}/{file:[a-z0-9][a-z0-9-]{0,60}}.pmtiles")
    @PreAuthorize("@access.canSomewhere(authentication, 'parcel.read')")
    ResponseEntity<Resource> archive(@PathVariable String kind, @PathVariable String file) {
        BackgroundKind background = kind(kind);
        if (background == null) {
            return ResponseEntity.notFound().build();
        }
        return catalogue.archive(background, file + ".pmtiles")
                .map(path -> file(path, PMTILES))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/geographic/fonts/{fontstack}/{range}.pbf")
    @PreAuthorize("@access.canSomewhere(authentication, 'parcel.read')")
    ResponseEntity<Resource> glyphs(@PathVariable String fontstack, @PathVariable String range) {
        return catalogue.glyphs(fontstack, range)
                .map(path -> file(path, PROTOBUF))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private static ResponseEntity<Resource> file(Path path, MediaType type) {
        return ResponseEntity.ok()
                .contentType(type)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                .body(new FileSystemResource(path));
    }

    private static BackgroundKind kind(String segment) {
        for (BackgroundKind kind : BackgroundKind.values()) {
            if (kind.needsData() && kind.directory().equals(segment.toLowerCase(Locale.ROOT))) {
                return kind;
            }
        }
        return null;
    }
}
