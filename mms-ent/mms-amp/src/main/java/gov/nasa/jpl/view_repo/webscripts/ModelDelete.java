package gov.nasa.jpl.view_repo.webscripts;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.Date;
import java.util.UUID;

import javax.servlet.http.HttpServletResponse;

import gov.nasa.jpl.mbee.util.TimeUtils;
import gov.nasa.jpl.view_repo.util.*;
import org.alfresco.repo.model.Repository;
import org.alfresco.repo.security.authentication.AuthenticationUtil;
import org.alfresco.service.ServiceRegistry;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.springframework.extensions.webscripts.Cache;
import org.springframework.extensions.webscripts.Status;
import org.springframework.extensions.webscripts.WebScriptRequest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonElement;

import gov.nasa.jpl.mbee.util.Timer;
import gov.nasa.jpl.view_repo.util.LogUtil;

// delete everything it contains
public class ModelDelete extends AbstractJavaWebScript {
    static Logger logger = Logger.getLogger(ModelDelete.class);

    @Override protected boolean validateRequest(WebScriptRequest req, Status status) {
        // TODO Auto-generated method stub
        return false;
    }

    public ModelDelete() {
        super();
    }

    public ModelDelete(Repository repositoryHelper, ServiceRegistry registry) {
        super(repositoryHelper, registry);
    }

    /**
     * Entry point
     */
    @Override protected Map<String, Object> executeImpl(WebScriptRequest req, Status status, Cache cache) {
        ModelDelete instance = new ModelDelete(repository, getServices());
        return instance.executeImplImpl(req, status, cache);
    }

    @Override protected Map<String, Object> executeImplImpl(WebScriptRequest req, Status status, Cache cache) {
        String user = AuthenticationUtil.getFullyAuthenticatedUser();
        printHeader(user, logger, req);
        Timer timer = new Timer();

        Map<String, Object> model = new HashMap<>();
        JsonObject result = null;

        try {
            result = handleRequest(req, status, user);
            if (result != null) {
                model.put(Sjm.RES, result);
            } else {
                status.setCode(responseStatus.getCode());
                model.put(Sjm.RES, createResponseJson());
            }
        } catch (Exception e) {
            logger.error(String.format("%s", LogUtil.getStackTrace(e)));
        }

        printFooter(user, logger, timer);

        return model;
    }

    protected JsonObject handleRequest(WebScriptRequest req, final Status status, String user) throws IOException {
        JsonObject result = new JsonObject();
        String date = TimeUtils.toTimestamp(new Date().getTime());

        JsonObject res = new JsonObject();
        String commitId = UUID.randomUUID().toString();
        JsonObject commit = new JsonObject();
        commit.addProperty(Sjm.ELASTICID, commitId);
        JsonArray commitDeleted = new JsonArray();
        JsonArray deletedElements = new JsonArray();

        String projectId = getProjectId(req);
        String refId = getRefId(req);

        Set<String> ids = new HashSet<>();
        EmsNodeUtil emsNodeUtil = new EmsNodeUtil(projectId, refId);
        Set<String> elasticIds = new HashSet<>();
        String elementId = req.getServiceMatch().getTemplateVars().get("elementId");
        if (elementId != null && !elementId.contains("holding_bin") && !elementId.contains("view_instances_bin")) {
            ids.add(elementId);
        } else {
            JsonParser parser = new JsonParser();
            try {
            	JsonElement requestJsonElement = parser.parse(req.getContent().getContent());
                JsonObject requestJson = requestJsonElement.isJsonNull() ? new JsonObject()
                    : requestJsonElement.getAsJsonObject();
                this.populateSourceApplicationFromJson(requestJson);
                if (requestJson.has(Sjm.ELEMENTS)) {
                    JsonArray elementsJson = requestJson.get(Sjm.ELEMENTS).getAsJsonArray();
                    if (elementsJson != null) {
                        for (int ii = 0; ii < elementsJson.size(); ii++) {
                            String id = elementsJson.get(ii).getAsJsonObject().get(Sjm.SYSMLID).getAsString();
                            if (!id.contains("holding_bin") && !id.contains("view_instances_bin")) {
                                ids.add(id);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                response.append("Could not parse request body");
                log(Level.ERROR, HttpServletResponse.SC_BAD_REQUEST, String.format("%s", LogUtil.getStackTrace(e)));
                return null;
            }
        }

        for (String id : ids) {
            if (emsNodeUtil.getNodeBySysmlid(id).size() > 0) {
                JsonArray nodesToDelete = emsNodeUtil.getChildren(id);
                for (int i = 0; i < nodesToDelete.size(); i++) {
                    JsonObject node = nodesToDelete.get(i).getAsJsonObject();
                    if (!elasticIds.contains(node.get(Sjm.ELASTICID).getAsString())) {
                        deletedElements.add(node);
                        JsonObject obj = new JsonObject();
                        obj.add(Sjm.SYSMLID, node.get(Sjm.SYSMLID));
                        obj.add(Sjm.ELASTICID, node.get(Sjm.ELASTICID));
                        commitDeleted.add(obj);
                        elasticIds.add(node.get(Sjm.ELASTICID).getAsString());
                    }
                    log(Level.DEBUG, String.format("Node: %s", node));
                }
            }
        }

        if (deletedElements.size() > 0) {
            result.add("addedElements", new JsonArray());
            result.add("updatedElements", new JsonArray());
            result.add("deletedElements", deletedElements);
            commit.add("added", new JsonArray());
            commit.add("updated", new JsonArray());
            commit.add("deleted", commitDeleted);
            commit.addProperty(Sjm.CREATOR, user);
            commit.addProperty(Sjm.CREATED, date);
            result.add("commit", commit);
            if (CommitUtil.sendDeltas(result, projectId, refId, requestSourceApplication, services, false)) {
                if (!elasticIds.isEmpty()) {
                    emsNodeUtil.updateElasticRemoveRefs(elasticIds);
                }
                res.add(Sjm.ELEMENTS, deletedElements);
                res.addProperty(Sjm.CREATOR, user);
                res.addProperty(Sjm.COMMITID, commitId);
            } else {
                log(Level.ERROR, HttpServletResponse.SC_BAD_REQUEST, "Commit failed, please check server logs for failed items");
                return null;
            }
        }
        return res;
    }
}
