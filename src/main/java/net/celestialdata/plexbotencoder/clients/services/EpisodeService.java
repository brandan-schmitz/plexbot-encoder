package net.celestialdata.plexbotencoder.clients.services;

import net.celestialdata.plexbotencoder.clients.models.Episode;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import javax.inject.Singleton;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

@Singleton
@Path("/episodes")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RegisterRestClient(configKey = "AppSettings.apiAddress")
public interface EpisodeService {

    @GET
    @Path("/{id}")
    Episode get(@PathParam("id") String id);
}