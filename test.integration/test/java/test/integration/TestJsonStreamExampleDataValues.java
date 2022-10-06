package test.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static test.integration.Utils.asJsonInput;

import java.util.Date;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import se.jbee.json.stream.JsonStream;

class TestJsonStreamExampleDataValues {

  interface DataValueSet {

    String dataSet();

    Date completeDate();

    String period();

    String orgUnit();

    String attributeOptionCombo();
    // ...

    Stream<DataValue> dataValues();
  }

  interface DataValue {
    String dataElement();

    String period();

    String orgUnit();

    String categoryOptionCombo();

    String attributeOptionCombo();

    String value();

    String storedBy();

    Date created();

    Date lastUpdated();

    String comment();
  }

  private static final String JSON = // language=JSON
      """
          {
              "dataSet": "dataSetID",
              "completeDate": "date",
              "period": "period",
              "orgUnit": "orgUnitID",
              "attributeOptionCombo": "aocID",
              "dataValues": [
                  {
                      "dataElement": "dataElementID",
                      "categoryOptionCombo": "cocID",
                      "value": "1",
                      "comment": "comment1"
                  },
                  {
                      "dataElement": "dataElementID",
                      "categoryOptionCombo": "cocID",
                      "value": "2",
                      "comment": "comment2"
                  },
                  {
                      "dataElement": "dataElementID",
                      "categoryOptionCombo": "cocID",
                      "value": "3",
                      "comment": "comment3"
                  }
              ]
          }
          """;

  @Test
  void testDataValueSetImport() {
    DataValueSet set = JsonStream.ofRoot(DataValueSet.class, asJsonInput(JSON));

    assertEquals("dataSetID", set.dataSet());
    assertEquals("orgUnitID", set.orgUnit());

    assertEquals(
        List.of(1, 2, 3), set.dataValues().map(DataValue::value).map(Integer::parseInt).toList());
  }
}
