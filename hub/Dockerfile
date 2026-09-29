# Tandem Hub: tandemd running headless in a container. Build from the repo root:
#   docker build -f hub/Dockerfile -t tandem-hub .

FROM rust:1-slim-bookworm AS builder
WORKDIR /src
COPY Cargo.toml Cargo.lock ./
COPY crates ./crates
# Cache mounts keep the registry and the compiled dependencies between builds. The
# binary is copied out inside the same RUN because the target dir is not part of the image.
RUN --mount=type=cache,target=/usr/local/cargo/registry \
    --mount=type=cache,target=/src/target \
    cargo build --release --locked -p tandemd \
 && cp target/release/tandemd /usr/local/bin/tandemd

FROM debian:bookworm-slim
# The same glibc as the builder, so the binary runs as built.
RUN groupadd --system --gid 10001 tandem \
 && useradd --system --uid 10001 --gid 10001 --home-dir /data --no-create-home tandem \
 && mkdir -p /data/inbox \
 && chown -R tandem:tandem /data
COPY --from=builder /usr/local/bin/tandemd /usr/local/bin/tandemd

ENV TANDEM_NAME="Tandem Hub"
# A named volume takes its initial ownership from this directory, so the non-root
# user can write to it. A bind mount needs a matching chown.
VOLUME /data
USER tandem
EXPOSE 47820/udp

# tandemd only stops cleanly on SIGINT. As PID 1 a container ignores SIGTERM unless
# the process handles it, so docker stop would wait ten seconds and then kill it.
STOPSIGNAL SIGINT

# The shell is only there to expand $TANDEM_NAME; exec makes tandemd PID 1 again.
# The flags are global options, so they go before the subcommand.
ENTRYPOINT ["/bin/sh", "-c", "exec tandemd --data-dir /data --download-dir /data/inbox --name \"$TANDEM_NAME\" run"]
