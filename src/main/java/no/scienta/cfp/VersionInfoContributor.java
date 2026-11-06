package no.scienta.cfp;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.actuate.info.Info;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.core.SpringVersion;
import org.springframework.stereotype.Component;

/**
 * Puts the running versions on /actuator/info.
 */
@Component
public class VersionInfoContributor implements InfoContributor {

    @Override
    public void contribute(Info.Builder builder) {
        Map<String, Object> versions = new LinkedHashMap<>();
        versions.put("java", System.getProperty("java.version"));
        versions.put("springBoot", SpringBootVersion.getVersion());
        versions.put("springFramework", SpringVersion.getVersion());
        builder.withDetail("versions", versions);
    }
}
