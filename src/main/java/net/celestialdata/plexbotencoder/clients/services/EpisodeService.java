package net.celestialdata.plexbotencoder.clients.services;

import net.celestialdata.plexbotencoder.clients.AuthorizationHeaderFactory;
import net.celestialdata.plexbotencoder.clients.models.Episode;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.rest.client.annotation.RegisterClientHeaders;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import javax.inject.Singleton;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.InputStream;

@Singleton
@Path("/api/v1/episodes")
@RegisterRestClient(configKey = "AppSettings.apiAddress")
@RegisterClientHeaders(AuthorizationHeaderFactory.class)
public interface EpisodeService {

    @GET
    @Retry()
    @Path("/{tvdb_id}")
    @Produces(MediaType.APPLICATION_JSON)
    Episode get(@PathParam("tvdb_id") long id);

    @GET
    @Path("/download/{id}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    Response downloadFile(@PathParam("id") int id);

    @POST
    @Path("/upload")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    Response uploadFile(@HeaderParam("Content-Length") long fileSize, @HeaderParam("Content-Id") int id, InputStream data);
}