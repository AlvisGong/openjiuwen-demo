/*
 * Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.
 */

package com.example.session;

import com.openjiuwen.core.common.constants.Constant;
import com.openjiuwen.core.common.exception.ErrorHelper;
import com.openjiuwen.core.common.exception.StatusCode;
import com.openjiuwen.core.common.logging.Loggers;
import com.openjiuwen.core.graph.pregel.PregelConstants;
import com.openjiuwen.core.graph.store.GraphStoreState;
import com.openjiuwen.core.graph.store.Store;
import com.openjiuwen.core.session.BaseSession;
import com.openjiuwen.core.session.checkpointer.Checkpointer;
import com.openjiuwen.core.session.checkpointer.CheckpointerProvider;
import com.openjiuwen.core.session.constants.SessionConstants;
import com.openjiuwen.core.session.interaction.InteractiveInput;
import com.openjiuwen.core.session.internal.NodeSession;
import com.openjiuwen.core.session.state.WorkflowCommitState;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * File-based checkpointer that persists session state to local files using Java serialization.
 * <p>
 * Mirrors {@link com.openjiuwen.core.session.checkpointer.InMemoryCheckpointer} logic,
 * replacing {@link java.util.concurrent.ConcurrentHashMap} with file I/O.
 *
 * <h3>Directory structure</h3>
 * <pre>
 * ./file_checkpoint_data/
 *   &lt;sessionId&gt;/
 *     agent/
 *       &lt;agentId&gt;.ser       (Java serialized agent state)
 *     workflow/
 *       &lt;workflowId&gt;.ser    (Java serialized workflow state + updates)
 *     graph/
 *       &lt;ns&gt;.ser            (Java serialized GraphStoreState)
 * </pre>
 */
public class FileCheckpointer extends Checkpointer {

    private final Path baseDir;

    public FileCheckpointer(Path baseDir) {
        this.baseDir = baseDir.toAbsolutePath().normalize();
    }

    /**
     * Provider for factory registration.
     */
    public static CheckpointerProvider provider() {
        return conf -> {
            Object dirObj = conf != null ? conf.get("dir") : null;
            Path dir;
            if (dirObj instanceof String s && !s.isBlank()) {
                dir = Path.of(s);
            } else if (dirObj instanceof Path p) {
                dir = p;
            } else {
                dir = Path.of("./file_checkpoint_data");
            }
            return new FileCheckpointer(dir);
        };
    }

    // =========================================================================
    // Agent lifecycle
    // =========================================================================

    @Override
    public void preAgentExecute(BaseSession session, Object inputs) {
        String sessionId = session.sessionId();
        FileAgentStorage agentStore = new FileAgentStorage(sessionId);

        Loggers.SESSION.info("Create new file agent checkpointer store, sessionId={}", sessionId);
        Loggers.SESSION.info("Begin to restore agent session, sessionId={}", sessionId);
        agentStore.recover(session);
        Loggers.SESSION.info("Succeed to restore agent session, sessionId={}", sessionId);

        if (inputs != null) {
            List<Object> inputList = new ArrayList<>();
            inputList.add(inputs);
            session.state().update(Map.of(Constant.INTERACTIVE_INPUT, inputList));
        }
    }

    @Override
    public void postAgentExecute(BaseSession session) {
        String sessionId = session.sessionId();
        FileAgentStorage agentStore = new FileAgentStorage(sessionId);

        Loggers.SESSION.info("Save agent checkpoint on completion, sessionId={}", sessionId);
        agentStore.save(session);
        Loggers.SESSION.info("Succeed to save agent checkpoint on completion, sessionId={}", sessionId);
    }

    @Override
    public void interruptAgentExecute(BaseSession session) {
        String sessionId = session.sessionId();
        FileAgentStorage agentStore = new FileAgentStorage(sessionId);

        Loggers.SESSION.info("Save agent checkpoint on interruption, sessionId={}", sessionId);
        agentStore.save(session);
        Loggers.SESSION.info("Succeed to save agent checkpoint on interruption, sessionId={}", sessionId);
    }

    // =========================================================================
    // Workflow lifecycle
    // =========================================================================

    @Override
    public void preWorkflowExecute(BaseSession session, InteractiveInput inputs) {
        String sessionId = session.sessionId();
        String workflowId = getWorkflowId(session);

        FileWorkflowStorage workflowStore = new FileWorkflowStorage(sessionId);
        boolean isNewStore = !workflowStore.directoryExists();

        if (isNewStore) {
            Loggers.SESSION.info("Create new workflow checkpointer store, sessionId={}, workflowId={}",
                    sessionId, workflowId);
        }

        if (inputs != null) {
            Loggers.SESSION.info("Begin to restore workflow session, sessionId={}, workflowId={}",
                    sessionId, workflowId);
            workflowStore.recover(workflowId, session, inputs);
            Loggers.SESSION.info("Succeed to restore workflow session, sessionId={}, workflowId={}",
                    sessionId, workflowId);
        } else {
            if (!workflowStore.isExists(workflowId)) {
                return;
            }
            Object forceDelete = session.config() != null
                    ? session.config().getEnv(SessionConstants.FORCE_DEL_WORKFLOW_STATE_KEY, false)
                    : false;
            if (Boolean.TRUE.equals(forceDelete)) {
                Loggers.SESSION.info("Force clearing workflow checkpoints, sessionId={}, workflowId={}",
                        sessionId, workflowId);
                workflowStore.clear(workflowId);
                graphStore().delete(sessionId, workflowId);
            } else {
                throw ErrorHelper.buildError(StatusCode.CHECKPOINTER_PRE_WORKFLOW_EXECUTION_ERROR,
                        "session_id", sessionId,
                        "workflow", workflowId,
                        "reason", "workflow state exists but non-interactive input and cleanup is disabled");
            }
        }
    }

    @Override
    public void postWorkflowExecute(BaseSession session, Object result, Exception exception) {
        String sessionId = session.sessionId();
        String workflowId = getWorkflowId(session);
        FileWorkflowStorage workflowStore = new FileWorkflowStorage(sessionId);

        if (exception != null) {
            if (!workflowStore.directoryExists()) {
                throw ErrorHelper.buildError(StatusCode.CHECKPOINTER_POST_WORKFLOW_EXECUTION_ERROR,
                        "workflow", workflowId,
                        "reason", "workflow store not found");
            }
            saveWorkflowCheckpoint(workflowId, sessionId, session, "workflow exception");
            if (exception instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(exception);
        }

        if (result instanceof Map<?, ?> resultMap && resultMap.containsKey(PregelConstants.TASK_STATUS_INTERRUPT)) {
            saveWorkflowCheckpoint(workflowId, sessionId, session, "workflow interruption");
            return;
        }

        // Normal completion - clear checkpoints
        Loggers.SESSION.info("Clear workflow checkpoints on completion, sessionId={}, workflowId={}",
                sessionId, workflowId);
        graphStore().delete(sessionId, workflowId);
        workflowStore.clear(workflowId);
    }

    // =========================================================================
    // Session management
    // =========================================================================

    @Override
    public boolean sessionExists(String sessionId) {
        return Files.exists(agentDir(sessionId)) || Files.exists(workflowDir(sessionId));
    }

    @Override
    public void release(String sessionId) {
        // Delete graph data
        Path graphDir = graphDir(sessionId);
        if (Files.exists(graphDir)) {
            try {
                deleteDirectory(graphDir);
            } catch (IOException e) {
                Loggers.SESSION.warn("Failed to delete graph directory on release, sessionId={}", sessionId, e);
            }
        }
        // Delete workflow data
        Path wfDir = workflowDir(sessionId);
        if (Files.exists(wfDir)) {
            try {
                deleteDirectory(wfDir);
            } catch (IOException e) {
                Loggers.SESSION.warn("Failed to delete workflow directory on release, sessionId={}", sessionId, e);
            }
        }
        // Delete agent data
        Path agDir = agentDir(sessionId);
        if (Files.exists(agDir)) {
            try {
                deleteDirectory(agDir);
            } catch (IOException e) {
                Loggers.SESSION.warn("Failed to delete agent directory on release, sessionId={}", sessionId, e);
            }
        }
        // Delete session directory
        Path sessionDir = sessionDir(sessionId);
        try {
            deleteDirectory(sessionDir);
        } catch (IOException e) {
            Loggers.SESSION.warn("Failed to delete session directory on release, sessionId={}", sessionId, e);
        }
        Loggers.SESSION.info("Cleared all checkpoints on release, sessionId={}", sessionId);
    }

    @Override
    public Store graphStore() {
        return new FileGraphStore();
    }

    // =========================================================================
    // Internal helpers
    // =========================================================================

    private void saveWorkflowCheckpoint(String workflowId, String sessionId,
                                         BaseSession session, String reason) {
        FileWorkflowStorage workflowStore = new FileWorkflowStorage(sessionId);
        Loggers.SESSION.info("Save workflow checkpoint on {}, sessionId={}, workflowId={}",
                reason, sessionId, workflowId);
        workflowStore.save(workflowId, session);
        Loggers.SESSION.info("Succeed to save workflow checkpoint on {}, sessionId={}, workflowId={}",
                reason, sessionId, workflowId);
    }

    // ---- Directory path helpers ----

    private Path sessionDir(String sessionId) {
        return baseDir.resolve(sanitize(sessionId));
    }

    private Path agentDir(String sessionId) {
        return sessionDir(sessionId).resolve("agent");
    }

    private Path workflowDir(String sessionId) {
        return sessionDir(sessionId).resolve("workflow");
    }

    private Path graphDir(String sessionId) {
        return sessionDir(sessionId).resolve("graph");
    }

    /**
     * Sanitize session/workflow IDs for use as directory/file names.
     */
    private static String sanitize(String id) {
        return id.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    // =========================================================================
    // Serialization helpers
    // =========================================================================

    @SuppressWarnings("unchecked")
    private static Map<String, Object> loadMap(Path file) throws IOException, ClassNotFoundException {
        try (ObjectInputStream in = new ObjectInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            return (Map<String, Object>) in.readObject();
        }
    }

    private static void saveMap(Path file, Map<String, Object> map) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (ObjectOutputStream out = new ObjectOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            out.writeObject(map);
        }
    }

    private static void deleteFile(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException ignored) {
        }
    }

    private static void deleteDirectory(Path dir) throws IOException {
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                for (Path child : stream.toList()) {
                    deleteDirectory(child);
                }
            }
        }
        Files.deleteIfExists(dir);
    }

    // =========================================================================
    // Inner Storage Classes
    // =========================================================================

    /**
     * File-based agent storage.
     */
    private class FileAgentStorage {
        private final String sessionId;

        FileAgentStorage(String sessionId) {
            this.sessionId = sessionId;
        }

        void save(BaseSession session) {
            String agentId = session.sessionId();
            Map<String, Object> state = session.state().getState();
            if (state != null) {
                try {
                    saveMap(agentFile(agentId), state);
                } catch (IOException e) {
                    throw new UncheckedIOException("Failed to save agent state: " + agentId, e);
                }
            }
        }

        void recover(BaseSession session) {
            String agentId = session.sessionId();
            Path file = agentFile(agentId);
            if (!Files.exists(file)) {
                return;
            }
            try {
                Map<String, Object> state = loadMap(file);
                session.state().setState(state);
            } catch (IOException | ClassNotFoundException e) {
                Loggers.SESSION.warn("Failed to recover agent state, agentId={}", agentId, e);
            }
        }

        private Path agentFile(String agentId) {
            return agentDir(sessionId).resolve(sanitize(agentId) + ".ser");
        }
    }

    /**
     * File-based workflow storage.
     */
    private class FileWorkflowStorage {
        private final String sessionId;

        FileWorkflowStorage(String sessionId) {
            this.sessionId = sessionId;
        }

        void save(String workflowId, BaseSession session) {
            Map<String, Object> blob = new LinkedHashMap<>();
            Map<String, Object> state = session.state().getState();
            if (state != null) {
                blob.put("state", state);
            }

            if (session.state() instanceof WorkflowCommitState workflowState) {
                Map<String, Object> updates = workflowState.getUpdates();
                blob.put("updates", updates);
            }

            try {
                saveMap(workflowFile(workflowId), blob);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to save workflow state: " + workflowId, e);
            }
        }

        @SuppressWarnings("unchecked")
        void recover(String workflowId, BaseSession session, InteractiveInput inputs) {
            Path file = workflowFile(workflowId);
            if (!Files.exists(file)) {
                return;
            }
            try {
                Map<String, Object> blob = loadMap(file);
                Map<String, Object> state = (Map<String, Object>) blob.get("state");
                if (state != null) {
                    session.state().setState(state);
                }

                if (inputs != null) {
                    processInteractiveInputs(session, inputs);
                }

                Map<String, Object> updates = (Map<String, Object>) blob.get("updates");
                if (updates != null && session.state() instanceof WorkflowCommitState workflowState) {
                    workflowState.setUpdates(updates);
                    workflowState.commit();
                }
            } catch (IOException | ClassNotFoundException e) {
                Loggers.SESSION.warn("Failed to recover workflow state, workflowId={}", workflowId, e);
            }
        }

        void clear(String workflowId) {
            deleteFile(workflowFile(workflowId));
            // Clean up empty parent dirs
            Path dir = workflowDir(sessionId);
            try {
                try (var stream = Files.list(dir)) {
                    if (stream.findAny().isEmpty()) {
                        deleteDirectory(dir);
                    }
                }
            } catch (IOException ignored) {
            }
        }

        boolean isExists(String workflowId) {
            return Files.exists(workflowFile(workflowId));
        }

        boolean directoryExists() {
            return Files.exists(workflowDir(sessionId));
        }

        private Path workflowFile(String workflowId) {
            return workflowDir(sessionId).resolve(sanitize(workflowId) + ".ser");
        }

        @SuppressWarnings("unchecked")
        private void processInteractiveInputs(BaseSession session, InteractiveInput inputs) {
            if (inputs.getRawInputs() != null) {
                if (session.state() instanceof WorkflowCommitState workflowState) {
                    workflowState.updateAndCommitWorkflowState(
                            Map.of(Constant.INTERACTIVE_INPUT, inputs.getRawInputs()));
                }
                return;
            }

            for (Map.Entry<String, Object> entry : inputs.getUserInputs().entrySet()) {
                NodeSession nodeSession = new NodeSession(session, entry.getKey());
                Object interactiveInput = nodeSession.state().get(Constant.INTERACTIVE_INPUT);
                List<Object> inputList;
                if (interactiveInput instanceof List<?> existingInputs) {
                    inputList = new ArrayList<>(existingInputs.size() + 1);
                    inputList.addAll((List<Object>) existingInputs);
                    inputList.add(entry.getValue());
                } else {
                    inputList = List.of(entry.getValue());
                }
                nodeSession.state().update(Map.of(Constant.INTERACTIVE_INPUT, inputList));
            }

            if (session.state() instanceof WorkflowCommitState workflowState) {
                workflowState.commit();
            }
        }
    }

    /**
     * File-based graph store implementing {@link Store}.
     */
    private class FileGraphStore implements Store {

        @Override
        public Optional<GraphStoreState> get(String sessionId, String ns) {
            Path file = graphFile(sessionId, ns);
            if (!Files.exists(file)) {
                return Optional.empty();
            }
            try (ObjectInputStream in = new ObjectInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
                Object obj = in.readObject();
                if (obj instanceof GraphStoreState state) {
                    return Optional.of(state);
                }
                return Optional.empty();
            } catch (IOException | ClassNotFoundException e) {
                Loggers.SESSION.warn("Failed to load graph state, sessionId={}, ns={}", sessionId, ns, e);
                return Optional.empty();
            }
        }

        @Override
        public void save(String sessionId, String ns, GraphStoreState state) {
            Path file = graphFile(sessionId, ns);
            try {
                Path parent = file.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to create graph directory", e);
            }
            try (ObjectOutputStream out = new ObjectOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
                out.writeObject(state);
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to save graph state: " + file, e);
            }
        }

        @Override
        public void delete(String sessionId, String ns) {
            Path graphDir = graphDir(sessionId);
            if (!Files.exists(graphDir)) {
                return;
            }

            if (ns == null) {
                try {
                    deleteDirectory(graphDir);
                } catch (IOException e) {
                    Loggers.SESSION.warn("Failed to delete graph directory, sessionId={}", sessionId, e);
                }
            } else {
                String safeNs = sanitize(ns);
                // Delete by prefix match (matching InMemoryStore behavior)
                try (var stream = Files.list(graphDir)) {
                    for (Path file : stream.toList()) {
                        String name = file.getFileName().toString();
                        if (name.startsWith(safeNs) && name.endsWith(".ser")) {
                            deleteFile(file);
                        }
                    }
                } catch (IOException e) {
                    Loggers.SESSION.warn("Failed to list graph directory, sessionId={}", sessionId, e);
                }
            }
        }

        private Path graphFile(String sessionId, String ns) {
            return graphDir(sessionId).resolve(sanitize(ns) + ".ser");
        }
    }
}
