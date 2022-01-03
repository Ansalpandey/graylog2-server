/*
 * Copyright (C) 2020 Graylog, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Server Side Public License, version 1,
 * as published by MongoDB, Inc.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * Server Side Public License for more details.
 *
 * You should have received a copy of the Server Side Public License
 * along with this program. If not, see
 * <http://www.mongodb.com/licensing/server-side-public-license>.
 */
package org.graylog.plugins.map.geoip;

import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableMap;
import com.google.common.net.InetAddresses;
import org.graylog.plugins.map.config.GeoIpResolverConfig;
import org.graylog2.plugin.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;
import java.net.InetAddress;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static com.codahale.metrics.MetricRegistry.name;

public class GeoIpResolverEngine {
    private static final Logger LOG = LoggerFactory.getLogger(GeoIpResolverEngine.class);

    /**
     * A mapping of fields that to search that contain IP addresses.  ONLY these fields will be checked
     * to see if they have valid Geo IP information.
     */
    private final Map<String, String> ipAddressFields = new ImmutableMap.Builder<String, String>()
            .put("source_ip", "source")
            .put("host_ip", "host")
            .put("destination_ip", "destination")
            .build();

    private final GeoIpResolver<GeoLocationInformation> ipLocationResolver;
    private final GeoIpResolver<GeoAsnInformation> ipAsnResolver;
    private boolean enabled;


    public GeoIpResolverEngine(GeoIpVendorResolverService resolverService, GeoIpResolverConfig config, MetricRegistry metricRegistry) {
        Timer resolveTime = metricRegistry.timer(name(GeoIpResolverEngine.class, "resolveTime"));

        ipLocationResolver = resolverService.createCityResolver(config, resolveTime);
        ipAsnResolver = resolverService.createAsnResolver(config, resolveTime);

        LOG.info("Created Geo IP Resolvers for '{}'", config.databaseVendorType());
        LOG.info("'{}' Status Enabled: {}", ipLocationResolver.getClass().getSimpleName(), ipLocationResolver.isEnabled());
        LOG.info("'{}' Status Enabled: {}", ipAsnResolver.getClass().getSimpleName(), ipAsnResolver.isEnabled());

        this.enabled = ipLocationResolver.isEnabled() || ipAsnResolver.isEnabled();

    }

    public boolean filter(Message message) {
        if (!enabled) {
            return false;
        }

        List<String> ipFields = getIpAddressFields(message);

        for (String key : ipFields) {
            Object fieldValue = message.getField(key);
            final InetAddress address = getValidRoutableInetAddress(fieldValue);
            if (address == null) {
                continue;
            }

            //TODO: Tag any reserved IP addresses with 'reserved_ip: true' once ReservedIpChecker is merged

            final String prefix = ipAddressFields.get(key);
            ipLocationResolver.getGeoIpData(address).ifPresent(locationInformation -> {
                message.addField(prefix + "_geo_coordinates", locationInformation.latitude() + "," + locationInformation.longitude());
                message.addField(prefix + "_geo_country", locationInformation.countryIsoCode());
                message.addField(prefix + "_geo_city", locationInformation.cityName());
                message.addField(prefix + "_geo_region", locationInformation.region());
                message.addField(prefix + "_geo_timeZone", locationInformation.timeZone());

                if (!(locationInformation.countryIsoCode() == null || locationInformation.cityName() == null)) {
                    String name = String.format(Locale.ENGLISH, "%s, %s", locationInformation.cityName(), locationInformation.countryIsoCode());
                    message.addField(prefix + "_geo_name", name);
                }
            });

            ipAsnResolver.getGeoIpData(address).ifPresent(info -> {

                message.addField(prefix + "_as_organization", info.organization());
                message.addField(prefix + "_as_number", info.asn());
            });

        }

        return true;
    }

    private List<String> getIpAddressFields(Message message) {
        return message.getFieldNames()
                .stream()
                .filter(e -> ipAddressFields.containsKey(e)
                        && !e.startsWith(Message.INTERNAL_FIELD_PREFIX))
                .collect(Collectors.toList());
    }

    private InetAddress getValidRoutableInetAddress(Object fieldValue) {
        final InetAddress ipAddress;
        if (fieldValue instanceof InetAddress) {
            ipAddress = (InetAddress) fieldValue;
        } else if (fieldValue instanceof String) {
            ipAddress = getIpFromFieldValue((String) fieldValue);
        } else {
            ipAddress = null;
        }
        return ipAddress;
    }

    @Nullable
    @VisibleForTesting
    InetAddress getIpFromFieldValue(String fieldValue) {
        try {
            return InetAddresses.forString(fieldValue.trim());
        } catch (IllegalArgumentException e) {
            // Do nothing, field is not an IP
        }

        return null;
    }
}
