#install scala
wget http://www.scala-lang.org/files/archive/scala-2.10.3.tgz http://repo.scala-sbt.org/scalasbt/sbt-native-packages/org/scala-sbt/sbt/0.13.0/sbt.tgz http://repo.typesafe.com/typesafe/ivy-releases/org.scala-sbt/sbt-launch/0.13.0/sbt-launch.jar
tar -xvf scala-2.10.3.tgz
tar -xvf sbt.tgz
sudo ln -s /usr/share/scala/bin/scala /usr/bin/scala
sudo ln -s /usr/share/scala/bin/scalac /usr/bin/scalac
sudo ln -s /usr/share/scala/bin/fsc /usr/bin/fsc
sudo ln -s /usr/share/scala/bin/sbaz /usr/bin/sbaz
sudo ln -s /usr/share/scala/bin/sbaz-setup /usr/bin/sbaz-setup
sudo ln -s /usr/share/scala/bin/scaladoc /usr/bin/scaladoc
sudo ln -s /usr/share/scala/bin/scalap /usr/bin/scalap

#install jvm
sudo yum install java-1.7.0-openjdk
#install git
sudo yum install curl-devel expat-devel gettext-devel   openssl-devel zlib-devel
#clone repo
git clone https://github.com/Emregul/Akka_PaxOS.git
#install sbt

sudo mv sbt-launch.jar /usr/local/bin/sbt-launcher.jar
echo "java -Xmx512M -jar /usr/local/bin/sbt-launcher.jar \"\$@\"" | sudo tee /usr/local/bin/sbt
sudo chmod +x /usr/local/bin/sbt

