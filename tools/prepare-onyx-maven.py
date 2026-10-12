from pathlib import Path
from shutil import copy2
from xml.etree import ElementTree as ET

source = Path.home()/'.gradle/caches/modules-2/files-2.1/com.onyx.android.sdk'
target = Path.home()/'.local/share/scrawl-build/onyx-maven/com/onyx/android/sdk'
versions = {
    'onyxsdk-device': '1.3.2',
    'onyxsdk-baselite': '1.1.0.1',
    'onyxsdk-base': '1.8.3',
    'onyxsdk-commons-io': '2.5',
    'onyxsdk-penbrush': '1.1.0.1',
    'onyxsdk-pen': '1.5.1',
}
deps = {
    'onyxsdk-device': [('com.alibaba.fastjson2','fastjson2','2.0.48.android8'),('androidx.annotation','annotation','1.0.0')],
    'onyxsdk-baselite': [('org.jetbrains.kotlin','kotlin-stdlib-jdk8','1.6.10')],
    'onyxsdk-commons-io': [],
    'onyxsdk-penbrush': [('com.onyx.android.sdk','onyxsdk-baselite','1.1.0.1'),('org.jetbrains.kotlin','kotlin-stdlib-jdk8','1.6.10')],
    'onyxsdk-pen': [('com.onyx.android.sdk','onyxsdk-base','1.8.3'),('com.onyx.android.sdk','onyxsdk-penbrush','1.1.0.1'),('de.ruedigermoeller','fst','2.56'),('org.jetbrains.kotlin','kotlin-stdlib-jdk8','1.6.10')],
    'onyxsdk-base': [
        ('com.onyx.android.sdk','onyxsdk-device','1.3.2'),
        ('com.onyx.android.sdk','onyxsdk-baselite','1.1.0.1'),
        ('com.squareup.retrofit2','retrofit','2.1.0'),
        ('com.squareup.okhttp3','logging-interceptor','4.10.0'),
        ('com.alibaba.fastjson2','fastjson2','2.0.48.android8'),
        ('pub.devrel','easypermissions','0.2.1'),
        ('com.onyx.android.sdk','onyxsdk-commons-io','2.5'),
        ('commons-io','commons-io','2.13.0'),
        ('org.apache.commons','commons-text','1.4'),
        ('commons-codec','commons-codec','1.13'),
        ('io.reactivex.rxjava2','rxjava','2.1.13'),
        ('io.reactivex.rxjava2','rxandroid','2.1.0'),
        ('androidx.dynamicanimation','dynamicanimation','1.1.0-alpha03'),
        ('org.greenrobot','eventbus','3.0.0'),
        ('com.tencent','mmkv','1.0.19'),
        ('net.lingala.zip4j','zip4j','2.11.5'),
        ('de.ruedigermoeller','fst','2.56'),
        ('joda-time','joda-time','2.10.14'),
        ('com.fasterxml.uuid','java-uuid-generator','4.1.0'),
        ('androidx.databinding','databinding-common','4.1.3'),
        ('androidx.databinding','databinding-runtime','4.1.3'),
        ('androidx.databinding','databinding-adapters','4.1.3'),
        ('org.jetbrains.kotlin','kotlin-stdlib-jdk8','1.6.10'),
        ('com.jakewharton.hugo.fix','hugo-annotations','1.2.3'),
    ],
}
for artifact,version in versions.items():
    found=list((source/artifact/version).glob(f'*/{artifact}-{version}.aar'))
    assert len(found)==1, (artifact,found)
    directory=target/artifact/version
    directory.mkdir(parents=True,exist_ok=True)
    copy2(found[0],directory/f'{artifact}-{version}.aar')
    root=ET.Element('project',xmlns='http://maven.apache.org/POM/4.0.0')
    for key,value in [('modelVersion','4.0.0'),('groupId','com.onyx.android.sdk'),('artifactId',artifact),('version',version),('packaging','aar')]:
        ET.SubElement(root,key).text=value
    dependencies=ET.SubElement(root,'dependencies')
    for group,name,depversion in deps[artifact]:
        element=ET.SubElement(dependencies,'dependency')
        for key,value in [('groupId',group),('artifactId',name),('version',depversion)]:
            ET.SubElement(element,key).text=value
    ET.ElementTree(root).write(directory/f'{artifact}-{version}.pom',encoding='utf-8',xml_declaration=True)
print('prepared',len(versions),'BOOX modules in',target)

# These three AARs are transitive BOOX dependencies that the BOOX Maven server
# also served for the Mac release build. Keep them in the same host-local repo.
for group, artifact, version in [
    ('pub.devrel', 'easypermissions', '0.2.1'),
    ('com.tencent', 'mmkv', '1.0.19'),
    ('com.jakewharton.hugo.fix', 'hugo-annotations', '1.2.3'),
]:
    found = list((source.parent / group / artifact / version).glob(f'*/{artifact}-{version}.aar'))
    assert len(found) == 1, (group, artifact, found)
    directory = target.parents[3] / group.replace('.', '/') / artifact / version
    directory.mkdir(parents=True, exist_ok=True)
    copy2(found[0], directory / f'{artifact}-{version}.aar')
    root = ET.Element('project', xmlns='http://maven.apache.org/POM/4.0.0')
    for key, value in [('modelVersion', '4.0.0'), ('groupId', group), ('artifactId', artifact), ('version', version), ('packaging', 'aar')]:
        ET.SubElement(root, key).text = value
    if artifact == 'mmkv':
        dependencies = ET.SubElement(root, 'dependencies')
        dependency = ET.SubElement(dependencies, 'dependency')
        for key, value in [('groupId', 'com.android.support'), ('artifactId', 'support-annotations'), ('version', '28.0.0')]:
            ET.SubElement(dependency, key).text = value
    ET.ElementTree(root).write(directory / f'{artifact}-{version}.pom', encoding='utf-8', xml_declaration=True)
print('prepared 3 transitive BOOX AARs')
