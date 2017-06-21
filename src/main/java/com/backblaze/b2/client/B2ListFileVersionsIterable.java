/*
 * Copyright 2017, Backblaze Inc. All Rights Reserved.
 * License https://www.backblaze.com/using_b2_code.html
 */
package com.backblaze.b2.client;

import com.backblaze.b2.client.exceptions.B2Exception;
import com.backblaze.b2.client.structures.B2FileVersion;
import com.backblaze.b2.client.structures.B2ListFileVersionsRequest;
import com.backblaze.b2.client.structures.B2ListFileVersionsResponse;
import com.backblaze.b2.client.structures.B2ListFilesResponse;

import java.util.Iterator;

public class B2ListFileVersionsIterable extends B2ListFilesIterableBase {
    private final B2ListFileVersionsRequest request;

    private class Iter extends IterBase {
        private B2ListFileVersionsResponse currentResponse;

        Iter() throws B2Exception {
        }

        @Override
        protected B2ListFilesResponse getCurrentResponseOrNull() {
            return currentResponse;
        }

        @Override
        protected void advance() throws B2Exception {
            B2ListFileVersionsRequest.Builder builder =
                    B2ListFileVersionsRequest.builder(request);

            if (currentResponse != null) {
                builder.setStart(currentResponse.getNextFileName(),
                        currentResponse.getNextFileId());
            }

            currentResponse = getClient().listFileVersions(builder.build());
        }
    }

    public B2ListFileVersionsIterable(B2StorageClientImpl b2Client,
                                   B2ListFileVersionsRequest request) {
        super(b2Client);
        this.request = request;
    }

    @Override
    Iterator<B2FileVersion> createIter() throws B2Exception {
        return new Iter();
    }
}
