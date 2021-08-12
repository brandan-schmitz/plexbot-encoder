package net.celestialdata.plexbotencoder.clients.services;

import net.celestialdata.plexbotencoder.clients.models.Movie;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import javax.inject.Singleton;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

@Singleton
@Path("/movies")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RegisterRestClient(configKey = "AppSettings.apiAddress")
public interface MovieService {

    @GET
    @Retry()
    @Path("/{tmdb_id}")
    Movie get(@PathParam("tmdb_id") long id);
}