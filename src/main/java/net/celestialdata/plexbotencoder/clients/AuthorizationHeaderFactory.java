package net.celestialdata.plexbotencoder.clients;

import org.eclipse.microprofile.config.ConfigProvider;
import org.eclipse.microprofile.rest.client.ext.ClientHeadersFactory;

import javax.enterprise.context.ApplicationScoped;
import javax.ws.rs.core.MultivaluedHashMap;
import javax.ws.rs.core.MultivaluedMap;
import java.util.Base64;

@ApplicationScoped
public class AuthorizationHeaderFactory implements ClientHeadersFactory {

    @Override
    public MultivaluedMap<String, String> update(MultivaluedMap<String, String> incomingHeaders, MultivaluedMap<String, String> clientOutgoingHeaders) {
        MultivaluedMap<String, String> result = new MultivaluedHashMap<>();

        // Add basic login header
        result.add("Authorization", "Basic " + Base64.getEncoder().encodeToString((
                ConfigProvider.getConfig().getValue("AppSettings.username", String.class) +
                        ":" + ConfigProvider.getConfig().getValue("AppSettings.password", String.class)).getBytes()));

        // Return the headers with the added auth header
        return result;
    }
}
