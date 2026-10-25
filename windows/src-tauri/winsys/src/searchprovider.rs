//! Results of this app in the search of a Linux desktop, which asks over the session bus: GNOME Shell through
//! `org.gnome.Shell.SearchProvider2` (found by the `.ini` that the package puts in /usr/share/gnome-shell/search-providers), KRunner of
//! KDE through `org.kde.krunner1` (found by a `.desktop` file in the user's own krunner/dbusplugins). Both are answered from what the
//! app already has in memory: the desktop waits for the answer while the person types.

use std::collections::HashMap;
use std::sync::Arc;

use zbus::blocking::Connection;
use zbus::blocking::connection::Builder;
use zbus::interface;
use zbus::zvariant::{OwnedValue, Value};

pub const BUS_NAME: &str = "nl.markmaaktmedia.tandem.SearchProvider";
pub const GNOME_PATH: &str = "/nl/markmaaktmedia/tandem/SearchProvider";
pub const KRUNNER_PATH: &str = "/nl/markmaaktmedia/tandem/KRunner";

/// What KRunner needs to find the runner on the bus. Plasma 5.24 and 6 read it from the user's data folder.
/// The file keeps the name it had when it only found contacts: a second file next to an old one would ask Tandem twice.
pub const KRUNNER_DESKTOP: &str = "[Desktop Entry]
Name=Tandem
Comment=Your devices, what they can do, messages, contacts, the clipboard, shared files and settings
Type=Service
Icon=phone
X-KDE-ServiceTypes=Plasma/Runner
X-KDE-PluginInfo-Name=tandem-contacts
X-KDE-PluginInfo-EnabledByDefault=true
X-Plasma-API=DBus
X-Plasma-API-Minimum-Version=2
X-Plasma-DBusRunner-Service=nl.markmaaktmedia.tandem.SearchProvider
X-Plasma-DBusRunner-Path=/nl/markmaaktmedia/tandem/KRunner
";

/// How a result is shown: its name, the line under it and the name of an icon of the theme (without `-symbolic`, which GNOME adds).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Meta {
    pub id: String,
    pub name: String,
    pub description: String,
    pub icon: String,
}

/// What the search of the desktop asks of the app.
pub trait Source: Send + Sync + 'static {
    /// The results for what was typed, split in words, best first.
    fn search(&self, terms: &[String]) -> Vec<String>;
    /// How each result that is still there is shown.
    fn metas(&self, ids: &[String]) -> Vec<Meta>;
    fn activate(&self, id: &str);
    /// The app itself was asked for (GNOME: a click on its icon next to the results).
    fn launch(&self);
}

struct Gnome(Arc<dyn Source>);

#[interface(name = "org.gnome.Shell.SearchProvider2")]
impl Gnome {
    fn get_initial_result_set(&self, terms: Vec<String>) -> Vec<String> {
        self.0.search(&terms)
    }

    /// A narrower search after one more letter. Searching again is as fast as filtering and finds the same.
    fn get_subsearch_result_set(&self, _previous: Vec<String>, terms: Vec<String>) -> Vec<String> {
        self.0.search(&terms)
    }

    fn get_result_metas(&self, ids: Vec<String>) -> Vec<HashMap<String, OwnedValue>> {
        self.0
            .metas(&ids)
            .into_iter()
            .map(|m| {
                let mut meta = HashMap::new();
                let mut put = |key: &str, value: Value<'_>| {
                    if let Ok(value) = OwnedValue::try_from(value) {
                        meta.insert(key.to_string(), value);
                    }
                };
                put("gicon", Value::from(format!("{}-symbolic", m.icon)));
                put("id", Value::from(m.id));
                put("name", Value::from(m.name));
                put("description", Value::from(m.description));
                meta
            })
            .collect()
    }

    fn activate_result(&self, id: String, _terms: Vec<String>, _timestamp: u32) {
        self.0.activate(&id);
    }

    fn launch_search(&self, _terms: Vec<String>, _timestamp: u32) {
        self.0.launch();
    }
}

struct Krunner(Arc<dyn Source>);

/// One match: id, text, icon name, category relevance (0 to 100), relevance (0 to 1), and the line under it in the properties.
type Match = (String, String, String, i32, f64, HashMap<String, OwnedValue>);

#[interface(name = "org.kde.krunner1")]
impl Krunner {
    fn actions(&self) -> Vec<(String, String, String)> {
        Vec::new()
    }

    #[zbus(name = "Match")]
    fn find(&self, query: String) -> Vec<Match> {
        let terms: Vec<String> = query.split_whitespace().map(str::to_string).collect();
        // Shorter than two letters finds half of everything.
        if query.trim().chars().count() < 2 {
            return Vec::new();
        }
        let ids = self.0.search(&terms);
        let count = ids.len().max(1) as f64;
        self.0
            .metas(&ids)
            .into_iter()
            .enumerate()
            .map(|(rank, m)| {
                let mut properties = HashMap::new();
                if let Ok(value) = OwnedValue::try_from(Value::from(m.description)) {
                    properties.insert("subtext".to_string(), value);
                }
                (m.id, m.name, m.icon, 70, 1.0 - rank as f64 / (count * 2.0), properties)
            })
            .collect()
    }

    fn run(&self, match_id: String, _action_id: String) {
        self.0.activate(&match_id);
    }
}

/// The name on the bus and the two interfaces behind it. Dropping it gives the name back, after which the desktop finds nobody to ask.
pub struct SearchProvider {
    _connection: Connection,
}

impl SearchProvider {
    /// `None` when there is no session bus or another Tandem has the name.
    pub fn start(source: Arc<dyn Source>) -> Option<SearchProvider> {
        let built = Builder::session()
            .and_then(|b| b.name(BUS_NAME))
            .and_then(|b| b.serve_at(GNOME_PATH, Gnome(source.clone())))
            .and_then(|b| b.serve_at(KRUNNER_PATH, Krunner(source)))
            .and_then(|b| b.build());
        match built {
            Ok(connection) => Some(SearchProvider { _connection: connection }),
            Err(error) => {
                log::warn!("the search of the desktop cannot ask Tandem: {error}");
                None
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    struct Fake(Mutex<Vec<String>>);

    impl Source for Fake {
        fn search(&self, terms: &[String]) -> Vec<String> {
            if terms.iter().any(|t| t == "anna") { vec!["phone/1".into()] } else { Vec::new() }
        }
        fn metas(&self, ids: &[String]) -> Vec<Meta> {
            ids.iter()
                .map(|id| Meta { id: id.clone(), name: "Anna".into(), description: "Mobile +31612345678".into(), icon: "call-start".into() })
                .collect()
        }
        fn activate(&self, id: &str) {
            self.0.lock().unwrap().push(id.to_string());
        }
        fn launch(&self) {}
    }

    /// Needs a session bus: `dbus-run-session -- cargo test`. Without one it says so and passes, like the test of the player.
    #[test]
    fn gnome_and_krunner_find_a_contact_and_picking_it_reaches_the_app() {
        if std::env::var_os("DBUS_SESSION_BUS_ADDRESS").is_none() {
            eprintln!("no session bus, so the search provider is not tried");
            return;
        }
        let fake = Arc::new(Fake(Mutex::new(Vec::new())));
        let provider = SearchProvider::start(fake.clone());
        assert!(provider.is_some(), "the provider could not be put on the bus");

        let client = Connection::session().unwrap();
        let proxy = |path: &'static str, interface: &'static str| {
            zbus::blocking::proxy::Builder::<zbus::blocking::Proxy>::new(&client)
                .destination(BUS_NAME)
                .unwrap()
                .path(path)
                .unwrap()
                .interface(interface)
                .unwrap()
                .cache_properties(zbus::proxy::CacheProperties::No)
                .build()
                .unwrap()
        };
        let gnome = proxy(GNOME_PATH, "org.gnome.Shell.SearchProvider2");
        let ids: Vec<String> = gnome.call("GetInitialResultSet", &(vec!["anna"],)).unwrap();
        assert_eq!(ids, vec!["phone/1"]);
        let metas: Vec<HashMap<String, OwnedValue>> = gnome.call("GetResultMetas", &(ids.clone(),)).unwrap();
        let name: String = metas[0]["name"].try_clone().unwrap().try_into().unwrap();
        assert_eq!(name, "Anna");
        let icon: String = metas[0]["gicon"].try_clone().unwrap().try_into().unwrap();
        assert_eq!(icon, "call-start-symbolic");
        let () = gnome.call("ActivateResult", &("phone/1", vec!["anna"], 0u32)).unwrap();

        let krunner = proxy(KRUNNER_PATH, "org.kde.krunner1");
        let matches: Vec<Match> = krunner.call("Match", &("anna",)).unwrap();
        assert_eq!(matches.len(), 1);
        assert_eq!((matches[0].0.as_str(), matches[0].1.as_str(), matches[0].2.as_str()), ("phone/1", "Anna", "call-start"));
        let () = krunner.call("Run", &("phone/1", "")).unwrap();
        assert_eq!(*fake.0.lock().unwrap(), vec!["phone/1", "phone/1"]);

        // Gone from the bus once dropped, as when the setting is turned off.
        // The bus notices the closed connection a moment later.
        drop(provider);
        let gone = (0..30).any(|_| {
            let answer: zbus::Result<Vec<String>> = gnome.call("GetInitialResultSet", &(vec!["anna"],));
            answer.is_err() || {
                std::thread::sleep(std::time::Duration::from_millis(100));
                false
            }
        });
        assert!(gone, "the name stayed on the bus");
    }
}
