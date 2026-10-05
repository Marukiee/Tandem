// Compiles the protobuf definitions of Quick Share (see proto/quickshare/README.md) into Rust. protox is a protobuf compiler
// written in Rust, so building needs no protoc on the machine.

fn main() {
    let dir = "proto/quickshare";
    let files = [
        "offline_wire_formats.proto",
        "wire_format.proto",
        "sharing_enums.proto",
        "securemessage.proto",
        "ukey.proto",
        "securegcm.proto",
        "device_to_device_messages.proto",
    ];
    println!("cargo:rerun-if-changed={dir}");
    let descriptors = protox::compile(files, [dir]).expect("the Quick Share protobuf files compile");
    prost_build::Config::new().compile_fds(descriptors).expect("the Quick Share protobuf types are generated");
}
