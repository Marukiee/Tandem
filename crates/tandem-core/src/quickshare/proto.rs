//! The protobuf messages of Quick Share, generated at build time from `proto/quickshare`. The modules follow the packages of the
//! definitions, because the generated code refers to its neighbours by those paths.

#![allow(clippy::all, dead_code, missing_docs)]

pub mod location {
    pub mod nearby {
        pub mod connections {
            include!(concat!(env!("OUT_DIR"), "/location.nearby.connections.rs"));
        }
        pub mod proto {
            pub mod sharing {
                include!(concat!(env!("OUT_DIR"), "/location.nearby.proto.sharing.rs"));
            }
        }
    }
}

pub mod nearby {
    pub mod sharing {
        pub mod service {
            pub mod proto {
                include!(concat!(env!("OUT_DIR"), "/nearby.sharing.service.proto.rs"));
            }
        }
    }
}

pub mod securegcm {
    include!(concat!(env!("OUT_DIR"), "/securegcm.rs"));
}

pub mod securemessage {
    include!(concat!(env!("OUT_DIR"), "/securemessage.rs"));
}

pub use location::nearby::connections;
pub use nearby::sharing::service::proto as sharing;
