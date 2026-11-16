package com.example.mediaupload.consumer;

import com.example.mediaupload.grpc.ActionReply;
import com.example.mediaupload.grpc.BeginUploadReply;
import com.example.mediaupload.grpc.BeginUploadRequest;
import com.example.mediaupload.grpc.CancelUploadRequest;
import com.example.mediaupload.grpc.MediaUploadServiceGrpc;
import com.example.mediaupload.grpc.UploadReply;
import com.example.mediaupload.grpc.VideoChunk;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;

/** gRPC endpoint called by producer clients. */
public final class GrpcUploadService extends MediaUploadServiceGrpc.MediaUploadServiceImplBase {
    private final UploadCoordinator coordinator;

    public GrpcUploadService(UploadCoordinator coordinator) {
        this.coordinator = coordinator;
    }

    @Override
    public void beginUpload(BeginUploadRequest request, StreamObserver<BeginUploadReply> responseObserver) {
        try {
            responseObserver.onNext(coordinator.beginUpload(request));
            responseObserver.onCompleted();
        } catch (RuntimeException e) {
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).withCause(e).asRuntimeException());
        }
    }

    @Override
    public StreamObserver<VideoChunk> uploadVideo(StreamObserver<UploadReply> responseObserver) {
        return new StreamObserver<>() {
            private String uploadId;
            private String streamToken;
            private boolean ended;

            @Override
            public void onNext(VideoChunk chunk) {
                if (ended) return;
                try {
                    if (uploadId == null) {
                        uploadId = chunk.getUploadId();
                        streamToken = chunk.getStreamToken();
                    }
                    if (!uploadId.equals(chunk.getUploadId()) || !streamToken.equals(chunk.getStreamToken())) {
                        throw new IllegalArgumentException("All chunks in one stream must use the same upload_id and stream token.");
                    }
                    coordinator.appendChunk(uploadId, streamToken, chunk.getChunkIndex(), chunk.getData().toByteArray());
                } catch (Exception e) {
                    ended = true;
                    if (uploadId != null) coordinator.abortReceiving(uploadId, streamToken, e.getMessage());
                    responseObserver.onError(Status.INVALID_ARGUMENT.withDescription(e.getMessage())
                            .withCause(e).asRuntimeException());
                }
            }

            @Override
            public void onError(Throwable error) {
                if (ended) return;
                ended = true;
                if (uploadId != null) coordinator.abortReceiving(uploadId, streamToken, error.getMessage());
            }

            @Override
            public void onCompleted() {
                if (ended) return;
                ended = true;
                if (uploadId == null) {
                    responseObserver.onNext(UploadReply.newBuilder().setStatus(com.example.mediaupload.grpc.UploadStatus.FAILED)
                            .setMessage("No chunks were received.").build());
                    responseObserver.onCompleted();
                    return;
                }
                responseObserver.onNext(coordinator.finishUpload(uploadId, streamToken));
                responseObserver.onCompleted();
            }
        };
    }

    @Override
    public void cancelUpload(CancelUploadRequest request, StreamObserver<ActionReply> responseObserver) {
        try {
            responseObserver.onNext(coordinator.cancelUpload(request.getUploadId()));
            responseObserver.onCompleted();
        } catch (RuntimeException e) {
            responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).withCause(e).asRuntimeException());
        }
    }
}
