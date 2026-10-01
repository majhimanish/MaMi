# Build from the repository root:  docker build -f server/Dockerfile -t mami-server .
FROM rust:1-slim-bookworm AS build
WORKDIR /src
COPY Cargo.toml Cargo.lock ./
COPY core core
COPY server server
COPY uniffi-bindgen uniffi-bindgen
RUN cargo build --release -p mami-server --locked

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/* \
    && mkdir /data && chown 65534:65534 /data
COPY --from=build /src/target/release/mami-server /usr/local/bin/mami-server
ENV MAMI_BIND=0.0.0.0:8080 \
    MAMI_DATABASE_URL=sqlite:///data/mami.db \
    MAMI_BLOB_DIR=/data/blobs
VOLUME /data
EXPOSE 8080
USER 65534
CMD ["mami-server"]
