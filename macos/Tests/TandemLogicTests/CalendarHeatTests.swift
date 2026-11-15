import XCTest
@testable import TandemLogic

final class CalendarHeatTests: XCTestCase {
    func testTheUsualIsTheRoundedMeanOfTheDaysWithAValue() {
        XCTAssertEqual(CalendarHeat.usual([57, nil, 58, 59, 58]), 58)
        XCTAssertEqual(CalendarHeat.usual([58, 59]), 59)
        XCTAssertNil(CalendarHeat.usual([nil, nil]))
    }

    func testHeartDaysAboveAreWarmBelowAreCoolAndNearTheUsualNeutral() {
        let levels = CalendarHeat.heart([60, 64, 56, 61, nil])
        XCTAssertEqual(levels[0], 0)
        XCTAssertEqual(levels[1]!, 1, accuracy: 0.001)
        XCTAssertEqual(levels[2]!, -1, accuracy: 0.001)
        XCTAssertEqual(levels[3], 0)
        XCTAssertNil(levels[4])
    }

    func testAMonthWithOneOrOnlyEqualValuesStaysNeutralAndNeverNaN() {
        XCTAssertEqual(CalendarHeat.heart([58]), [0])
        XCTAssertEqual(CalendarHeat.heart([58, 58, nil]), [0, 0, nil])
        XCTAssertEqual(CalendarHeat.heart([nil, nil]), [nil, nil])
        for level in CalendarHeat.heart([40, 200, 58, 59]).compactMap({ $0 }) {
            XCTAssertFalse(level.isNaN)
            XCTAssertTrue((-1...1).contains(level))
        }
    }

    func testSharesGrowTowardTheGoalAndStayInRange() {
        let shares = CalendarHeat.shares([0, 4000, 8000, 20000, nil], goal: 8000)
        XCTAssertEqual(shares[0]!, 0.15, accuracy: 0.001)
        XCTAssertEqual(shares[1]!, 0.575, accuracy: 0.001)
        XCTAssertEqual(shares[2], 1)
        XCTAssertEqual(shares[3], 1)
        XCTAssertNil(shares[4])
        XCTAssertTrue(CalendarHeat.met(8000, goal: 8000))
        XCTAssertFalse(CalendarHeat.met(7999, goal: 8000))
    }

    func testWithoutAGoalTheHighestDayIsFullAndZeroesDoNotDivideByZero() {
        let shares = CalendarHeat.shares([5, 10], goal: nil)
        XCTAssertEqual(shares[0]!, 0.575, accuracy: 0.001)
        XCTAssertEqual(shares[1], 1)
        XCTAssertEqual(CalendarHeat.shares([0, 0], goal: nil), [0.15, 0.15])
        XCTAssertEqual(CalendarHeat.shares([0], goal: 0), [0.15])
        XCTAssertFalse(CalendarHeat.met(0, goal: 0))
    }

    func testThePickedDayIsTodayWithAValueOrElseTheLastDayWithOne() {
        XCTAssertEqual(CalendarHeat.defaultPick([1, nil, 3, nil], today: 2), 2)
        XCTAssertEqual(CalendarHeat.defaultPick([1, nil, 3, nil], today: 3), 2)
        XCTAssertEqual(CalendarHeat.defaultPick([1, nil, 3, nil], today: nil), 2)
        XCTAssertNil(CalendarHeat.defaultPick([nil, nil], today: 1))
    }
}
