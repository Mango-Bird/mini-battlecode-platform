FROM alpine:3.22@sha256:5291449c3df73caf6ed85e649dec1b9e818b39a5d8c871e97afc13e9cd5e8fa8 AS build
RUN apk add --no-cache build-base cmake linux-headers
WORKDIR /build
COPY quickjs-ng-0.16.2.tar.gz /build/source.tar.gz
RUN echo "97c80625b26775a4c7ca618c004d4ea24cf99cbf867e4eba78bd927a8b23d106  source.tar.gz" | sha256sum -c - && tar -xf source.tar.gz
RUN cmake -S quickjs-0.16.2 -B qjs-build -DCMAKE_BUILD_TYPE=Release -DQJS_BUILD_LIBC=OFF -DBUILD_SHARED_LIBS=OFF && cmake --build qjs-build --target qjs -j2
COPY guest.c /build/guest.c
RUN cc -O2 -static -Wall -Wextra -Werror -Iquickjs-0.16.2 guest.c qjs-build/libqjs.a -lm -lpthread -o /guest
FROM scratch
COPY --from=build /guest /guest
COPY --from=build /build/quickjs-0.16.2/LICENSE /LICENSE
USER 65534:65534
ENTRYPOINT ["/guest"]
