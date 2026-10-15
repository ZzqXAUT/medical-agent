package com.example.mednav.agui;

public final class AgentEventType {

    public static final String RUN_STARTED = "RUN_STARTED";
    public static final String TEXT_MESSAGE_START = "TEXT_MESSAGE_START";
    public static final String TEXT_MESSAGE_CONTENT = "TEXT_MESSAGE_CONTENT";
    public static final String TEXT_MESSAGE_END = "TEXT_MESSAGE_END";
    public static final String RUN_FINISHED = "RUN_FINISHED";
    public static final String RUN_ERROR = "RUN_ERROR";
    public static final String AUTH_SECRET_REQUEST = "AUTH_SECRET_REQUEST";
    public static final String AUTH_RESULT = "AUTH_RESULT";
    public static final String PROFILE_UPDATED = "PROFILE_UPDATED";
    public static final String PIPELINE_PROGRESS = "PIPELINE_PROGRESS";
    public static final String CLINICAL_QUESTION = "CLINICAL_QUESTION";
    public static final String CLINICAL_RESULT = "CLINICAL_RESULT";
    public static final String LOCATION_STATUS = "LOCATION_STATUS";
    public static final String ROUTE_MATRIX_RESULT = "ROUTE_MATRIX_RESULT";
    public static final String NAVIGATION_CONTEXT_READY = "NAVIGATION_CONTEXT_READY";
    public static final String RECOMMENDATION_RESULT = "RECOMMENDATION_RESULT";
    public static final String LOCAL_REPAIR_PROGRESS = "LOCAL_REPAIR_PROGRESS";
    public static final String VALIDATION_FINDING = "VALIDATION_FINDING";
    public static final String SESSION_COMPLETED = "SESSION_COMPLETED";

    private AgentEventType() {}
}
