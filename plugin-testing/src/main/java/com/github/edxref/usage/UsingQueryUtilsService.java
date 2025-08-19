/*
 * User: eadno1
 * Date: 19/08/2025
 *
 * Copyright (2005) IDI. All rights reserved.
 * This software is a proprietary information of Israeli Direct Insurance.
 * Created by IntelliJ IDEA.
 */
package com.github.edxref.usage;

import com.github.edxref.util.QueryUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 *
 */
@Service
public class UsingQueryUtilsService {

	private final QueryUtils queryUtils;

	@Autowired
	public UsingQueryUtilsService(QueryUtils queryUtils) {
		this.queryUtils = queryUtils;
	}

	public String getManagerUserId() {
		return queryUtils.getQuery("getManagerUserId");
	}

	public String updateManagerUserId() {
		return queryUtils.getQuery("updateManagerUserId");
	}

	public String getUserId() {
		return queryUtils.getQuery("getUserId");
	}

}