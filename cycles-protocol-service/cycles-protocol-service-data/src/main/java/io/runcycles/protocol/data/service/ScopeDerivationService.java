package io.runcycles.protocol.data.service;

import io.runcycles.protocol.model.Subject;
import org.springframework.stereotype.Service;
import java.util.*;

/** Cycles Protocol v0.1.23 */
@Service
public class ScopeDerivationService {
    public List<String> deriveScopes(Subject subject) {
        List<String> scopes = new ArrayList<>();
        StringBuilder path = new StringBuilder();
        
        if (subject.getTenant() != null) {
            append(scopes, path, "tenant", subject.getTenant());
        }
        if (subject.getWorkspace() != null) {
            append(scopes, path, "workspace", subject.getWorkspace());
        }
        if (subject.getApp() != null) {
            append(scopes, path, "app", subject.getApp());
        }
        if (subject.getWorkflow() != null) {
            append(scopes, path, "workflow", subject.getWorkflow());
        }
        if (subject.getAgent() != null) {
            append(scopes, path, "agent", subject.getAgent());
        }
        if (subject.getToolGroup() != null) {
            append(scopes, path, "toolGroup", subject.getToolGroup());
        }
        
        return scopes;
    }
    
    private void append(List<String> scopes, StringBuilder path, String key, String value) {
        if (path.length() > 0) path.append("/");
        path.append(key).append(":").append(value);
        scopes.add(path.toString());
    }
}
