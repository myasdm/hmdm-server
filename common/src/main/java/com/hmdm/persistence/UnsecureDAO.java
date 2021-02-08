/*
 *
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hmdm.persistence;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hmdm.event.DeviceInfoUpdatedEvent;
import com.hmdm.persistence.domain.*;
import com.hmdm.persistence.mapper.*;
import com.hmdm.rest.json.LookupItem;
import com.hmdm.security.SecurityContext;
import com.hmdm.security.SecurityException;
import org.mybatis.guice.transactional.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * <p>A DAO which does not perform any security checks when accessing/updating data. It is intended for processing
 * requests from anonymous clients (for example, devices).</p>
 *
 * @author isv
 */
@Singleton
public class UnsecureDAO {

    private static final Logger logger = LoggerFactory.getLogger(UnsecureDAO.class);

    private final DeviceMapper deviceMapper;
    private final UserMapper userMapper;
    private final ConfigurationMapper configurationMapper;
    private final CommonMapper settingsMapper;
    private final ApplicationMapper applicationMapper;
    private final ApplicationDAO applicationDAO;
    private final ApplicationSettingDAO applicationSettingDAO;
    private final ConfigurationFileMapper configurationFileMapper;
    private final CustomerMapper customerMapper;

    /**
     * <p>Constructs new <code>UnsecureDAO</code> instance. This implementation does nothing.</p>
     */
    @Inject
    public UnsecureDAO(DeviceMapper deviceMapper,
                       UserMapper userMapper,
                       ConfigurationMapper configurationMapper,
                       CommonMapper settingsMapper,
                       ApplicationMapper applicationMapper,
                       ApplicationDAO applicationDAO,
                       ApplicationSettingDAO applicationSettingDAO,
                       ConfigurationFileMapper configurationFileMapper,
                       CustomerMapper customerMapper) {
        this.deviceMapper = deviceMapper;
        this.userMapper = userMapper;
        this.configurationMapper = configurationMapper;
        this.settingsMapper = settingsMapper;
        this.applicationMapper = applicationMapper;
        this.applicationDAO = applicationDAO;
        this.applicationSettingDAO = applicationSettingDAO;
        this.configurationFileMapper = configurationFileMapper;
        this.customerMapper = customerMapper;
    }

    public User findByLoginOrEmail(String login) {
        return userMapper.findByLogin(login);
    }

    public Device getDeviceByNumber(String number) {
        return this.deviceMapper.getDeviceByNumber(number);
    }

    public Device getDeviceByOldNumber(String number) {
        return this.deviceMapper.getDeviceByOldNumber(number);
    }

    public List<ApplicationSetting> getDeviceAppSettings(int deviceId) {
        final List<ApplicationSetting> appSettings
                = this.applicationSettingDAO.getApplicationSettingsByDeviceId(deviceId);
        return appSettings;
    }

    public void updateDeviceInfo(Integer id, String info, Long imeiUpdateTs) {
        this.deviceMapper.updateDeviceInfo(id, info, imeiUpdateTs);
    }

    public void updateDeviceCustomProperties(Integer id, Device device) {
        this.deviceMapper.updateDeviceCustomProperties(id, device.getCustom1(), device.getCustom2(), device.getCustom3());
    }

    public void completeDeviceMigration(Integer id) {
        this.deviceMapper.clearOldNumber(id);
    }

    // This method should be called in a single-tenant mode only
    // and the device customer ID should be set
    @Transactional
    public void insertDevice(Device device) {
        this.deviceMapper.insertDevice(device);
        if (device.getGroups() != null && !device.getGroups().isEmpty()) {
            this.deviceMapper.insertDeviceGroups(
                    device.getId(), device.getGroups().stream().map(LookupItem::getId).collect(Collectors.toList())
            );
        }
    }

    public List<Application> getPlainConfigurationApplications(Integer customerId, Integer id) {
        return this.configurationMapper.getPlainConfigurationApplications(customerId, id);
    }

    @Transactional
    public Configuration getConfigurationByIdWithAppSettings(Integer id) {
        final Configuration dbConfiguration = this.configurationMapper.getConfigurationById(id);
        if (dbConfiguration != null) {
            final List<ApplicationSetting> appSettings = this.applicationSettingDAO.getApplicationSettingsByConfigurationId(dbConfiguration.getId());
            dbConfiguration.setApplicationSettings(appSettings);
        }

        return dbConfiguration;
    }

    public Settings getSettings(int customerId) {
        return this.settingsMapper.getSettings(customerId);
    }

    public Settings getSingleCustomerSettings() {
        return this.settingsMapper.getSettings(1);
    }

    public List<Application> findByPackageIdAndVersion(Integer customerId, String pkg, String version) {
        return this.applicationMapper.findByPackageIdAndVersion(customerId, pkg, version);
    }

    /**
     * <p>Builds the lookup map from application package ID to application ID for specified packages and customer
     * account.</p>
     *
     * @param customerId an ID of a customer record.
     * @param appPackages a collection of application package IDs to build mapping for.
     * @return a mapping from application package ID to application ID.
     */
    public Map<String, Integer> buildPackageIdMapping(Integer customerId, Collection<String> appPackages) {
        if (appPackages == null || appPackages.isEmpty()) {
            return new HashMap<>();
        }

        List<LookupItem> ddd = this.applicationMapper.resolveAppsByPackageId(customerId, appPackages);
        return ddd.stream().collect(Collectors.toMap(LookupItem::getName, LookupItem::getId, (r1, r2) -> r1));
    }

    public void insertApplication(Application application) {
        final List<User> users = this.userMapper.findAll(application.getCustomerId());
        if (!users.isEmpty()) {
            final User user = users.stream().filter(u -> !u.getUserRole().isSuperAdmin()).findAny().orElse(users.get(0));
            logger.info("Using user account '{}' for setting up the security context when uploading application {} " +
                    "from mobile device", user.getLogin(), application);

            SecurityContext.init(user);
            try {
                this.applicationDAO.insertApplication(application);
            } finally {
                SecurityContext.release();
            }
        } else {
            throw new DAOException("No user accounts have been found mapped to requested customer account: #"
                    + application.getCustomerId());
        }
    }

    public Application findApplicationById(Integer appId) {
        Application app = this.applicationMapper.findById(appId);
        return app;
    }

    public ApplicationVersion findApplicationVersionById(Integer appId) {
        ApplicationVersion app = this.applicationMapper.findVersionById(appId);
        return app;
    }


    public Configuration getConfigurationByQRCodeKey(String id) {
        return this.configurationMapper.getConfigurationByQRCodeKey(id);
    }

    private static final Function<ApplicationSetting, String> appSettingMapKeyGenerator = (s) -> s.getApplicationPkg() + "," + s.getName();


    @Transactional
    public void saveDeviceApplicationSettings(Device dbDevice,
                                              List<ApplicationSetting> applicationSettings) {

        final Map<String, ApplicationSetting> dbDeviceAppSettingsMapping
                = this.applicationSettingDAO.getApplicationSettingsByDeviceId(dbDevice.getId())
                .stream()
                .collect(Collectors.toMap(appSettingMapKeyGenerator, s -> s, (r1, r2) -> r1));

        final Map<String, ApplicationSetting> appSettingsMapping
                = applicationSettings
                .stream()
                .filter(s -> s.getValue() != null && !s.getValue().trim().isEmpty())
                .collect(Collectors.toMap(appSettingMapKeyGenerator, s -> s, (r1, r2) -> r1));

        List<ApplicationSetting> mergedApplicationSettings = new ArrayList<>();

        dbDeviceAppSettingsMapping.values().forEach(dbSetting -> {
            final String dbSettingKey = appSettingMapKeyGenerator.apply(dbSetting);
            if (appSettingsMapping.containsKey(dbSettingKey)) {
                final ApplicationSetting appSetting = appSettingsMapping.get(dbSettingKey);
                if (appSetting.getLastUpdate() < dbSetting.getLastUpdate()) {
                    mergedApplicationSettings.add(dbSetting);
                } else {
                    mergedApplicationSettings.add(appSetting);
                }
            } else {
                mergedApplicationSettings.add(dbSetting);
            }
        });

        final Map<String, Application> appsMapping = this.applicationMapper.getAllApplications(dbDevice.getCustomerId())
                .stream()
                .collect(Collectors.toMap(Application::getPkg, a -> a, (r1, r2) -> r1));

        appSettingsMapping.values().forEach(appSetting -> {
            final String appSettingKey = appSettingMapKeyGenerator.apply(appSetting);
            if (!dbDeviceAppSettingsMapping.containsKey(appSettingKey)) {
                mergedApplicationSettings.add(appSetting);
            }
        });

        mergedApplicationSettings.forEach(appSetting -> {
            if (appSetting.getApplicationId() == null) {
                if (appsMapping.containsKey(appSetting.getApplicationPkg())) {
                    appSetting.setApplicationId(appsMapping.get(appSetting.getApplicationPkg()).getId());
                } else {
                    // TODO : Log a warning on unknown package ID
                }
            }
        });

        this.deviceMapper.deleteDeviceApplicationSettings(dbDevice.getId());
        if (!mergedApplicationSettings.isEmpty()) {
            final List<ApplicationSetting> validSettings = mergedApplicationSettings
                    .stream()
                    .filter(appSetting -> appSetting.getApplicationId() != null)
                    .collect(Collectors.toList());
            this.deviceMapper.insertDeviceApplicationSettings(dbDevice.getId(), validSettings);
        }
    }

    /**
     * <p>Saves the hash value for APK-file associated with the specified aplication version.</p>
     *
     * @param appVersionId an application version ID to save the hash value for APK file for.
     * @param hashValue a hash-value to be saved.
     */
    public void saveApkFileHash(Integer appVersionId, String hashValue) {
        this.applicationMapper.saveApkFileHash(appVersionId, hashValue);
    }

    /**
     * <p>Gets the device referenced by the specified ID.</p>
     *
     * @param id an ID of a device.
     * @return a device referenced by the specified ID or <code>null</code> if there is no such device found.
     */
    public Device getDeviceById(Integer id) {
        return this.deviceMapper.getDeviceById(id);
    }

    /**
     * <p>Gets the list of configuration files to be used on device.</p>
     *
     * @param device a device to get the configuration files for.
     * @return a list of configuration files to be used on device.
     */
    public List<ConfigurationFile> getConfigurationFiles(Device device) {
        return this.configurationFileMapper.getConfigurationFiles(device.getConfigurationId());
    }

//    /**
//     * <p>Gets the settings for the customer account mapped to specified device.</p>
//     *
//     * @param deviceId a device number identifying the device.
//     * @return the settings for related customer account.
//     */
//    public Settings getSettingsByDeviceId(String deviceId) {
//        return this.settingsMapper.getSettingsByDeviceId(deviceId);
//    }

    /**
     * <p>Tests if the current installation is single-customer</p>
     *
     * @return true if single-customer, false otherwise
     */
    public boolean isSingleCustomer() {
        return !customerMapper.isMultiTenant();
    }

    // This should only be called from the tasks, not from the web resource methods
    public List<Customer> getAllCustomersUnsecure() {
        return customerMapper.findAll();
    }
}
