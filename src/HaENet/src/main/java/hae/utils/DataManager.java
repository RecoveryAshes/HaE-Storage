package hae.utils;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.persistence.PersistedList;
import burp.api.montoya.persistence.PersistedObject;
import burp.api.montoya.persistence.Persistence;
import hae.component.board.message.MessageTableModel;
import hae.instances.http.utils.RegularMatcher;

import java.util.ArrayList;
import java.util.Set;
import java.util.function.Consumer;

public class DataManager {
    private final MontoyaApi api;
    private final Persistence persistence;

    public DataManager(MontoyaApi api) {
        this.api = api;
        this.persistence = api.persistence();
    }

    public synchronized void putData(String dataType, String dataName, PersistedObject persistedObject) {
        if (persistence.extensionData().getChildObject(dataName) != null) {
            persistence.extensionData().deleteChildObject(dataName);
        }
        persistence.extensionData().setChildObject(dataName, persistedObject);

        saveIndex(dataType, dataName);
    }

    public synchronized void loadData(MessageTableModel messageTableModel) {
        PersistedList<String> dataIndex = persistence.extensionData().getStringList("data");
        loadHaEData(dataIndex);
        messageTableModel.loadPersistedMessages();
    }

    private void saveIndex(String indexName, String indexValue) {
        PersistedList<String> indexList = persistence.extensionData().getStringList(indexName);

        if (indexList != null && !indexList.isEmpty()) {
            persistence.extensionData().deleteStringList(indexName);
        } else if (indexList == null) {
            indexList = PersistedList.persistedStringList();
        }

        if (!indexList.contains(indexValue)) {
            indexList.add(indexValue);
        }

        persistence.extensionData().setStringList(indexName, indexList);
    }

    private void loadHaEData(PersistedList<String> dataIndex) {
        if (dataIndex != null && !dataIndex.isEmpty()) {
            dataIndex.forEach(index -> {
                PersistedObject dataObj = persistence.extensionData().getChildObject(index);
                try {
                    dataObj.stringListKeys().forEach(dataKey -> RegularMatcher.updateGlobalMatchCache(api, index, dataKey, dataObj.getStringList(dataKey).stream().toList(), false));
                } catch (Exception ignored) {
                }
            });
        }
    }

    public synchronized void clearAllPersistedData() {
        try {
            PersistedObject extensionData = persistence.extensionData();

            deleteKeys(extensionData.childObjectKeys(), extensionData::deleteChildObject);
            deleteKeys(extensionData.stringKeys(), extensionData::deleteString);
            deleteKeys(extensionData.booleanKeys(), extensionData::deleteBoolean);
            deleteKeys(extensionData.byteKeys(), extensionData::deleteByte);
            deleteKeys(extensionData.shortKeys(), extensionData::deleteShort);
            deleteKeys(extensionData.integerKeys(), extensionData::deleteInteger);
            deleteKeys(extensionData.longKeys(), extensionData::deleteLong);
            deleteKeys(extensionData.byteArrayKeys(), extensionData::deleteByteArray);

            deleteKeys(extensionData.httpRequestKeys(), extensionData::deleteHttpRequest);
            deleteKeys(extensionData.httpResponseKeys(), extensionData::deleteHttpResponse);
            deleteKeys(extensionData.httpRequestResponseKeys(), extensionData::deleteHttpRequestResponse);

            deleteKeys(extensionData.stringListKeys(), extensionData::deleteStringList);
            deleteKeys(extensionData.booleanListKeys(), extensionData::deleteBooleanList);
            deleteKeys(extensionData.shortListKeys(), extensionData::deleteShortList);
            deleteKeys(extensionData.integerListKeys(), extensionData::deleteIntegerList);
            deleteKeys(extensionData.longListKeys(), extensionData::deleteLongList);
            deleteKeys(extensionData.byteArrayListKeys(), extensionData::deleteByteArrayList);
            deleteKeys(extensionData.httpRequestListKeys(), extensionData::deleteHttpRequestList);
            deleteKeys(extensionData.httpResponseListKeys(), extensionData::deleteHttpResponseList);
            deleteKeys(extensionData.httpRequestResponseListKeys(), extensionData::deleteHttpRequestResponseList);
        } catch (Exception e) {
            api.logging().logToError("clearAllPersistedData: " + e.getMessage());
        }
    }

    private void deleteKeys(Set<String> keys, Consumer<String> deleteAction) {
        if (keys == null || keys.isEmpty()) {
            return;
        }

        for (String key : new ArrayList<>(keys)) {
            try {
                deleteAction.accept(key);
            } catch (Exception ignored) {
            }
        }
    }
}
