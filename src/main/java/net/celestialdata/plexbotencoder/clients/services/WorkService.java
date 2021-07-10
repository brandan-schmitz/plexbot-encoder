package net.celestialdata.plexbotencoder.clients.services;

import net.celestialdata.plexbotencoder.clients.models.WorkItem;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;

import javax.inject.Singleton;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;

@Singleton
@Path("/encoding/work")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
@RegisterRestClient(configKey = "AppSettings.apiAddress")
public interface WorkService {

    @GET
    @Path("/{id}")
    WorkItem get(@PathParam("id") int id);

    @POST
    int create(WorkItem workItem);

    @PUT
    @Path("/{id}")
    WorkItem update(@PathParam("id") int id, WorkItem workItem);

    @DELETE
    @Path("/{id}")
    void delete(@PathParam("id") int id);
}