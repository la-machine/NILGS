// SNIGF/NILGS - Copyright (c) 2026 FLYT.
// Author: Pythagore.
package org.snigf.mvp.maps.application;

import java.nio.file.Path;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Map settings under {@code snigf.maps.*} (ADR-029).
 *
 * @param dataDirectory     where the tile archives and manifests are ({@code SNIGF_MAP_DATA_DIRECTORY};
 *                          {@code /maps}, a read-only volume, in the container)
 * @param externalProviders {@code none}: the only value accepted until an external provider is
 *                          approved by its own decision; anything else stops the application
 */
@Validated
@ConfigurationProperties(prefix = "snigf.maps")
public record MapProperties(@NotNull Path dataDirectory,
                            @NotNull @Pattern(regexp = "none", message = "external map providers are disabled (ADR-029): only 'none' is accepted")
                            String externalProviders) {
}
