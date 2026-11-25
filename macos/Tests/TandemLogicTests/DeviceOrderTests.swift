import Testing
import TandemLogic

struct DeviceOrderTests {
    @Test func theKeptOrderStaysWhateverConnectsFirst() {
        // The core puts the connected ones first: that must not move the rows.
        #expect(DeviceOrder.merge(kept: ["a", "b", "c"], current: ["c", "a", "b"]) == ["a", "b", "c"])
    }

    @Test func aNewDeviceGoesAtTheEndAndARemovedOneLeaves() {
        #expect(DeviceOrder.merge(kept: ["a", "b", "c"], current: ["c", "d", "a"]) == ["a", "c", "d"])
        #expect(DeviceOrder.merge(kept: [], current: ["b", "a"]) == ["b", "a"])
        #expect(DeviceOrder.merge(kept: ["a"], current: []) == [])
    }
}
