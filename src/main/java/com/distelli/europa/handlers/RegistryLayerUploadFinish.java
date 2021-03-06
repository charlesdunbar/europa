package com.distelli.europa.handlers;

import com.distelli.europa.EuropaRequestContext;
import org.eclipse.jetty.http.HttpMethod;
import com.distelli.webserver.RequestHandler;
import com.distelli.webserver.WebResponse;
import com.distelli.webserver.RequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import javax.inject.Singleton;
import java.util.Map;
import java.util.HashMap;
import javax.inject.Inject;
import lombok.extern.log4j.Log4j;
import com.distelli.europa.db.RegistryBlobDb;
import com.distelli.europa.models.RegistryBlob;
import com.distelli.europa.registry.RegistryError;
import com.distelli.europa.registry.RegistryErrorCode;
import org.bouncycastle.crypto.digests.SHA256Digest;
import static javax.xml.bind.DatatypeConverter.printHexBinary;
import java.util.List;
import java.util.ArrayList;
import com.distelli.objectStore.ObjectPartId;
import com.distelli.objectStore.ObjectStore;
import com.distelli.europa.models.RegistryBlobPart;
import com.distelli.objectStore.ObjectPartKey;
import javax.inject.Provider;

@Log4j
@Singleton
public class RegistryLayerUploadFinish extends RegistryLayerUploadChunk {
    @Inject
    private Provider<ObjectStore> _objectStoreProvider;
    @Inject
    private RegistryBlobDb _blobDb;
    public WebResponse handleRegistryRequest(EuropaRequestContext requestContext) {
        String ownerUsername = requestContext.getOwnerUsername();
        String ownerDomain = requestContext.getOwnerDomain();
        String name = requestContext.getMatchedRoute().getParam("name");
        String blobId = requestContext.getMatchedRoute().getParam("uuid");
        String digest = requestContext.getParameter("digest");
        if ( null == digest || ! digest.matches("^sha256:[0-9a-fA-F]{64}$") ) {
            throw new RegistryError("Invalid digest, must be sha256:<hex> got="+digest,
                                    RegistryErrorCode.DIGEST_INVALID);
        }
        digest = digest.toLowerCase();
        try {
            handleRegistryRequest(requestContext, true);
        } catch ( RuntimeException ex ) {
            throw ex;
        } catch ( Exception ex ) {
            throw new RuntimeException(ex);
        }
        RegistryBlob blob = _blobDb.getRegistryBlobById(blobId);
        if ( null == blob ) {
            throw new RegistryError("Concurrent upload finish detected for blobId="+blobId,
                                    RegistryErrorCode.BLOB_UPLOAD_INVALID);
        }
        validateDigest(digest, blob.getMdEncodedState());
        ObjectPartKey partKey = getObjectPartKey(blobId, blob.getUploadId());

        ObjectStore objectStore = _objectStoreProvider.get();
        objectStore.completePut(partKey, toObjectPartIds(blob.getPartIds()));
        _blobDb.finishUpload(blobId,
                             blob.getMdEncodedState(),
                             digest,
                             getTotalSize(blob.getPartIds()),
                             requestContext.getContentType());
        WebResponse response = new WebResponse(201);
        response.setResponseHeader("Location", joinWithSlash("/v2", ownerUsername, name, "blobs", digest));
        response.setResponseHeader("Docker-Content-Digest", digest);
        return response;
    }

    private List<ObjectPartId> toObjectPartIds(List<RegistryBlobPart> parts) {
        List<ObjectPartId> partIds = new ArrayList<>(parts.size());
        for ( RegistryBlobPart part : parts ) {
            partIds.add(ObjectPartId.builder()
                        .partNum(part.getPartNum())
                        .partId(part.getPartId())
                        .build());
        }
        return partIds;
    }

    private long getTotalSize(List<RegistryBlobPart> partIds) {
        if ( null == partIds ) return 0;
        return partIds.stream().mapToLong((part) -> part.getChunkSize()).sum();
    }

    private void validateDigest(String digest, byte[] encodedState) {
        SHA256Digest computedDigest = new SHA256Digest(encodedState);
        byte[] computedBytes = new byte[computedDigest.getDigestSize()];
        computedDigest.doFinal(computedBytes, 0);
        String computed = "sha256:" + printHexBinary(computedBytes);
        computed = computed.toLowerCase();
        if ( ! digest.equals(computed) ) {
            throw new RegistryError("Invalid digest, expected "+computed+", but got "+digest,
                                    RegistryErrorCode.DIGEST_INVALID);
        }
    }
}
