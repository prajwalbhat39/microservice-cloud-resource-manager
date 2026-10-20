package com.crm.quota.common.error;

/** 404 family: TENANT_NOT_FOUND, RESOURCE_NOT_FOUND, RESERVATION_NOT_FOUND, QUOTA_RESERVATION_NOT_FOUND. */
public class NotFoundException extends ApiException {

    public NotFoundException(ErrorCode code, String detail) {
        super(code, detail);
    }
}
