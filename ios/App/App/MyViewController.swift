//
//  MyViewController.swift
//  App
//
//  Created by Jaggar on 4/11/26.
//

import Foundation
import UIKit
import Capacitor

class MyViewController: CAPBridgeViewController {
    override open func capacitorDidLoad() {
        super.capacitorDidLoad()
        bridge?.registerPluginInstance(ShadowDevicePlugin())
       //bridge?.registerPluginInstance(ParadiseCallPlugin())
    }
}
